package io.github.atengk.boot.ssh.pool;

import io.github.atengk.boot.ssh.credential.SshKeyLoader;
import io.github.atengk.boot.ssh.exception.SshOperationException;
import io.github.atengk.boot.ssh.exception.SshResultCode;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import io.github.atengk.boot.ssh.properties.SshPoolProperties;
import io.github.atengk.boot.ssh.verifier.InMemoryServerKeyVerifier;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.forward.ExplicitPortForwardingTracker;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.core.CoreModuleProperties;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.Assert;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSH 工业级受管会话缓存池
 * <p>
 * 1. 深度复用已完成 TCP 握手、密钥协商与凭据认证的活跃 {@link ClientSession}，消除高达 200ms~500ms 的重复建连耗时；<br>
 * 2. 具备 LRU 淘汰上限（{@code max-sessions}）、空闲超时失效（{@code idle-timeout}）与后台探针心跳保活（{@code heartbeat-interval}）；<br>
 * 3. 提供 {@code executeOnce} 零残留瞬态隔离闭包与通信失败自动剔除机制，严格保障连接与物理句柄零泄漏。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
public class SshSessionPool implements AutoCloseable {

    private static final int STRIPE_COUNT = 64;
    private final Object[] stripeLocks;

    private final SshClient sshClient;
    private final SshPoolProperties poolProperties;

    /**
     * 活跃会话持有者缓存映射 (key: serverInfo.toPoolKey())
     */
    private final ConcurrentHashMap<String, PooledSessionHolder> pool = new ConcurrentHashMap<>();

    /**
     * 后台空闲会话探测淘汰调度器
     */
    private final ScheduledExecutorService evictionScheduler;
    private final ThreadPoolTaskScheduler internalTaskScheduler;

    private volatile boolean closed = false;

    public SshSessionPool(SshClient sshClient, SshPoolProperties poolProperties) {
        this(sshClient, poolProperties, null);
    }

    public SshSessionPool(SshClient sshClient, SshPoolProperties poolProperties, ScheduledExecutorService externalScheduler) {
        Assert.notNull(sshClient, "SshClient must not be null");
        this.sshClient = sshClient;
        this.poolProperties = poolProperties != null ? poolProperties : new SshPoolProperties();

        this.stripeLocks = new Object[STRIPE_COUNT];
        for (int i = 0; i < STRIPE_COUNT; i++) {
            this.stripeLocks[i] = new Object();
        }

        if (externalScheduler != null) {
            this.evictionScheduler = externalScheduler;
            this.internalTaskScheduler = null;
        } else {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("ateng-ssh-evictor-");
            scheduler.setDaemon(true);
            scheduler.setWaitForTasksToCompleteOnShutdown(false);
            scheduler.initialize();
            this.internalTaskScheduler = scheduler;
            this.evictionScheduler = scheduler.getScheduledExecutor();
        }

        // 启动定时空闲会话淘汰任务（周期取 idleTimeout 的一半或保底 15 秒）
        long checkIntervalSeconds = Math.max(5, Math.min(15, this.poolProperties.getIdleTimeout().toSeconds() / 2));
        this.evictionScheduler.scheduleWithFixedDelay(
                this::evictStaleSessions,
                checkIntervalSeconds,
                checkIntervalSeconds,
                TimeUnit.SECONDS
        );

        log.info("ateng-boot-ssh: SshSessionPool 会话缓存池初始化完成 [maxSessions={}, idleTimeout={}, heartbeat={}]",
                this.poolProperties.getMaxSessions(),
                this.poolProperties.getIdleTimeout(),
                this.poolProperties.getHeartbeatInterval());
    }

    private Object getLock(String poolKey) {
        if (poolKey == null) {
            return stripeLocks[0];
        }
        int hash = poolKey.hashCode() ^ (poolKey.hashCode() >>> 16);
        int index = Math.abs(hash % STRIPE_COUNT);
        return stripeLocks[index];
    }

    /**
     * 获取或创建与指定目标服务器绑定的池化可用会话（线程安全、防惊群）
     *
     * @param serverInfo 目标服务器连接描述符
     * @return 经过认证可直接使用的 ClientSession
     */
    public ClientSession acquire(SshServerInfo serverInfo) {
        Assert.notNull(serverInfo, "SshServerInfo must not be null");
        checkNotClosed();

        String poolKey = serverInfo.toPoolKey();
        long idleTimeoutMillis = poolProperties.getIdleTimeout().toMillis();

        // 1. 极速路径 (Fast Path)：无锁检查既有会话是否依然健康存活且未过期
        PooledSessionHolder holder = pool.get(poolKey);
        if (holder != null && holder.isAlive() && !holder.isExpired(idleTimeoutMillis)) {
            holder.touch();
            return holder.getSession();
        }

        // 2. 慢速路径 (Slow Path)：基于分段锁建立防惊群建连握手
        Object lock = getLock(poolKey);
        synchronized (lock) {
            checkNotClosed();
            holder = pool.get(poolKey);
            if (holder != null && holder.isAlive() && !holder.isExpired(idleTimeoutMillis)) {
                holder.touch();
                return holder.getSession();
            }

            // 若已有旧会话但已失效或过期，安全关闭并注销
            if (holder != null) {
                holder.close();
                pool.remove(poolKey);
            }

            // 容量防护：若池化数量达到上限，LRU 淘汰最久未访问会话
            evictLruIfNeeded();

            // 建立全新网络连接与认证
            ClientSession newSession = createAndAuthenticateSession(serverInfo);
            holder = new PooledSessionHolder(poolKey, serverInfo, newSession);
            pool.put(poolKey, holder);
            log.debug("ateng-boot-ssh: 成功建立并池化 SSH 会话 [poolKey={}]", poolKey);
            return newSession;
        }
    }

    /**
     * 在池化受管会话上下文中安全执行业务自定义逻辑（有返回值）
     * <p>
     * 具备通信失败自动感知与脏会话立即从池中剔除的能力。
     * </p>
     *
     * @param serverInfo 目标服务器描述符
     * @param callback   会话执行回调
     * @param <T>        返回值类型
     * @return 执行结果
     */
    public <T> T execute(SshServerInfo serverInfo, SshSessionCallback<T> callback) {
        Assert.notNull(serverInfo, "SshServerInfo must not be null");
        Assert.notNull(callback, "SshSessionCallback must not be null");

        ClientSession session = acquire(serverInfo);
        try {
            return callback.doInSession(session);
        } catch (Exception e) {
            // 当会话被关闭或发生通信断裂时，立即主动从池中剔除
            if (!session.isOpen() || isNetworkException(e)) {
                log.warn("ateng-boot-ssh: 检测到池化会话通信异常，主动剔除失效连接 [{}]: {}",
                        serverInfo.toSessionKey(), e.getMessage());
                evict(serverInfo);
            }
            if (e instanceof SshOperationException sshEx) {
                throw sshEx;
            }
            throw new SshOperationException(SshResultCode.OPERATION_FAILED, "SSH 会话操作执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 在池化受管会话上下文中安全执行业务自定义逻辑（无返回值）
     *
     * @param serverInfo 目标服务器描述符
     * @param action     会话执行逻辑
     */
    public void executeAction(SshServerInfo serverInfo, SshSessionAction action) {
        Assert.notNull(action, "SshSessionAction must not be null");
        execute(serverInfo, session -> {
            action.doInSession(session);
            return null;
        });
    }

    /**
     * 瞬态任务隔离执行闭包（有返回值，执行完毕确定性物理关闭断开，零连接与句柄残留）
     * <p>
     * 专职服务于一次性资产探测、敏感数据瞬时下发等无需长期持活且不期望占用池容量的高隔离场景。
     * </p>
     *
     * @param serverInfo 目标服务器描述符
     * @param callback   会话执行回调
     * @param <T>        返回值类型
     * @return 执行结果
     */
    public <T> T executeOnce(SshServerInfo serverInfo, SshSessionCallback<T> callback) {
        Assert.notNull(serverInfo, "SshServerInfo must not be null");
        Assert.notNull(callback, "SshSessionCallback must not be null");
        checkNotClosed();

        ClientSession transientSession = createAndAuthenticateSession(serverInfo);
        try {
            return callback.doInSession(transientSession);
        } catch (Exception e) {
            if (e instanceof SshOperationException sshEx) {
                throw sshEx;
            }
            throw new SshOperationException(SshResultCode.OPERATION_FAILED, "SSH 瞬态会话操作执行失败: " + e.getMessage(), e);
        } finally {
            try {
                transientSession.close(true);
                log.debug("ateng-boot-ssh: 瞬态会话已物理关闭断开 [{}]", serverInfo.toSessionKey());
            } catch (Exception ex) {
                log.warn("ateng-boot-ssh: 瞬态会话关闭失败 [{}]: {}", serverInfo.toSessionKey(), ex.getMessage());
            }
        }
    }

    /**
     * 瞬态任务隔离执行闭包（无返回值，执行完毕确定性物理关闭断开）
     *
     * @param serverInfo 目标服务器描述符
     * @param action     会话执行逻辑
     */
    public void executeOnceAction(SshServerInfo serverInfo, SshSessionAction action) {
        Assert.notNull(action, "SshSessionAction must not be null");
        executeOnce(serverInfo, session -> {
            action.doInSession(session);
            return null;
        });
    }

    /**
     * 主动将指定服务器描述符对应的池化会话注销并关闭
     *
     * @param serverInfo 目标描述符
     */
    public void evict(SshServerInfo serverInfo) {
        if (serverInfo != null) {
            evict(serverInfo.toPoolKey());
        }
    }

    /**
     * 根据会话池键值注销并关闭会话
     *
     * @param poolKey 缓存路由键
     */
    public void evict(String poolKey) {
        if (poolKey == null) {
            return;
        }
        Object lock = getLock(poolKey);
        synchronized (lock) {
            PooledSessionHolder holder = pool.remove(poolKey);
            if (holder != null) {
                holder.close();
                log.debug("ateng-boot-ssh: 已主动剔除并注销池化会话 [poolKey={}]", poolKey);
            }
        }
    }

    /**
     * 获取当前池中处于活跃可用状态的会话总数
     *
     * @return 活跃会话数
     */
    public int getActiveSessionCount() {
        int count = 0;
        for (PooledSessionHolder holder : pool.values()) {
            if (holder.isAlive()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 获取当前会话池包含的缓存项条目总数
     *
     * @return 缓存条目数
     */
    public int getPoolSize() {
        return pool.size();
    }

    /**
     * 清空并物理断开所有池化会话
     */
    public void clear() {
        log.info("ateng-boot-ssh: 正在清空并断开所有池化会话 (共 {} 个)...", pool.size());
        for (PooledSessionHolder holder : pool.values()) {
            holder.close();
        }
        pool.clear();
    }

    /**
     * 容器停机销毁回调：停止后台驱逐调度并释放全部底层网络连接
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        this.closed = true;
        log.info("ateng-boot-ssh: 正在关闭 SshSessionPool 会话缓存池并释放全部网络连接...");

        if (internalTaskScheduler != null) {
            try {
                internalTaskScheduler.destroy();
            } catch (Exception e) {
                log.warn("ateng-boot-ssh: 停止内部淘汰调度器异常: {}", e.getMessage());
            }
        } else if (evictionScheduler != null) {
            try {
                evictionScheduler.shutdownNow();
            } catch (Exception e) {
                log.warn("ateng-boot-ssh: 停止淘汰调度器异常: {}", e.getMessage());
            }
        }

        clear();
        log.info("ateng-boot-ssh: SshSessionPool 会话缓存池已彻底安全关闭");
    }

    private ClientSession createAndAuthenticateSession(SshServerInfo serverInfo) {
        ExplicitPortForwardingTracker jumpTracker = null;
        ClientSession session = null;
        try {
            // 构造建连初始上下文，确保在 TCP 握手与密钥协商阶段即刻生效目标地址与会话级 HostKeyPolicy
            Map<AttributeRepository.AttributeKey<?>, Object> initAttrs = new HashMap<>();
            initAttrs.put(InMemoryServerKeyVerifier.TARGET_HOST_KEY, serverInfo.toHostPortKey());
            if (serverInfo.getHostKeyPolicy() != null) {
                initAttrs.put(InMemoryServerKeyVerifier.HOST_KEY_POLICY_KEY, serverInfo.getHostKeyPolicy());
            }
            AttributeRepository connectionContext = AttributeRepository.ofAttributesMap(initAttrs);

            if (serverInfo.hasJumpHost()) {
                // 1. ProxyJump 跳板机穿透拓扑：递归解析并获取跳板机会话
                ClientSession jumpSession = acquire(serverInfo.getJumpHost());
                // 2. 在跳板机会话上建立本地动态端口转发（绑定本地随机可用端口 0，代理到目标 host:port）
                jumpTracker = jumpSession.createLocalPortForwardingTracker(
                        new SshdSocketAddress("127.0.0.1", 0),
                        new SshdSocketAddress(serverInfo.getHost(), serverInfo.getPort())
                );
                int boundPort = jumpTracker.getBoundAddress().getPort();
                log.debug("ateng-boot-ssh: ProxyJump 跳板机穿透本地临时转发就绪 [127.0.0.1:{} -> {} via {}]",
                        boundPort, serverInfo.toHostPortKey(), serverInfo.getJumpHost().toSessionKey());

                // 3. 通过本地代理端口与目标服务器握手建连
                session = sshClient.connect(serverInfo.getUsername(), "127.0.0.1", boundPort, connectionContext, null)
                        .verify(serverInfo.getConnectTimeout())
                        .getSession();

                // 4. 双向级联生命周期监听：目标会话关闭时即刻释放跳板机上的临时端口；跳板机会话关闭时级联切断目标会话
                ExplicitPortForwardingTracker finalTracker = jumpTracker;
                ClientSession targetSession = session;
                session.addCloseFutureListener(f -> {
                    try {
                        finalTracker.close();
                    } catch (Exception ex) {
                        log.debug("ateng-boot-ssh: 关闭跳板机临时转发隧道完成: {}", ex.getMessage());
                    }
                });
                jumpSession.addCloseFutureListener(f -> {
                    try {
                        if (targetSession.isOpen()) {
                            targetSession.close(true);
                        }
                    } catch (Exception ex) {
                        log.debug("ateng-boot-ssh: 跳板机会话关闭触发目标会话断开: {}", ex.getMessage());
                    }
                });
            } else {
                // 普通直连拓扑
                session = sshClient.connect(serverInfo.getUsername(), serverInfo.getHost(), serverInfo.getPort(), connectionContext, null)
                        .verify(serverInfo.getConnectTimeout())
                        .getSession();
            }

            // 注入真实目标服务器地址属性与策略备选守卫
            session.setAttribute(InMemoryServerKeyVerifier.TARGET_HOST_KEY, serverInfo.toHostPortKey());
            if (serverInfo.getHostKeyPolicy() != null) {
                session.setAttribute(InMemoryServerKeyVerifier.HOST_KEY_POLICY_KEY, serverInfo.getHostKeyPolicy());
                session.setServerKeyVerifier(serverInfo.getHostKeyPolicy().toVerifier());
            }

            // 统合注入四元身份凭据（密码/纯内存私钥/路径/KeyPair）
            SshKeyLoader.applyCredentials(session, serverInfo);
            session.auth().verify(serverInfo.getAuthTimeout());

            // 注入 keepalive 心跳探针参数
            Duration heartbeat = poolProperties.getHeartbeatInterval();
            if (heartbeat != null && !heartbeat.isZero()) {
                CoreModuleProperties.HEARTBEAT_INTERVAL.set(session, heartbeat);
            }

            return session;
        } catch (Exception e) {
            if (session != null) {
                try {
                    session.close(true);
                } catch (Exception ex) {
                    log.debug("ateng-boot-ssh: 建连失败清理异常会话完成: {}", ex.getMessage());
                }
            }
            if (jumpTracker != null) {
                try {
                    jumpTracker.close();
                } catch (Exception ignored) {
                }
            }
            throw translateException(e, serverInfo);
        }
    }

    private void evictLruIfNeeded() {
        if (pool.size() < poolProperties.getMaxSessions()) {
            return;
        }

        String oldestKey = null;
        long oldestAccessOrder = Long.MAX_VALUE;

        for (Map.Entry<String, PooledSessionHolder> entry : pool.entrySet()) {
            long order = entry.getValue().getAccessOrder();
            if (order < oldestAccessOrder) {
                oldestAccessOrder = order;
                oldestKey = entry.getKey();
            }
        }

        if (oldestKey != null) {
            PooledSessionHolder evicted = pool.remove(oldestKey);
            if (evicted != null) {
                log.info("ateng-boot-ssh: 会话池已满 (容量={})，LRU 淘汰最久未活跃会话 [{}]",
                        poolProperties.getMaxSessions(), evicted.getServerInfo().toSessionKey());
                evicted.close();
            }
        }
    }

    private void evictStaleSessions() {
        if (closed || pool.isEmpty()) {
            return;
        }

        long idleTimeoutMillis = poolProperties.getIdleTimeout().toMillis();
        for (Map.Entry<String, PooledSessionHolder> entry : pool.entrySet()) {
            PooledSessionHolder holder = entry.getValue();
            if (!holder.isAlive() || holder.isExpired(idleTimeoutMillis)) {
                log.debug("ateng-boot-ssh: 后台探测到失效或空闲超时会话，执行清理注销 [poolKey={}]", entry.getKey());
                pool.remove(entry.getKey(), holder);
                holder.close();
            }
        }
    }

    private void checkNotClosed() {
        if (closed) {
            throw new SshOperationException(SshResultCode.OPERATION_FAILED, "SshSessionPool 已关闭，拒绝获取新的会话");
        }
    }

    private boolean isNetworkException(Exception e) {
        return e instanceof IOException;
    }

    private SshOperationException translateException(Exception e, SshServerInfo serverInfo) {
        if (e instanceof SshOperationException sshEx) {
            return sshEx;
        }

        String message = e.getMessage() != null ? e.getMessage() : "";
        if (message.contains("Authentication") || message.contains("password") || message.contains("auth")) {
            return new SshOperationException(SshResultCode.AUTH_FAILED,
                    "SSH 身份凭据认证失败 [" + serverInfo.toSessionKey() + "]: " + message, e);
        }
        if (message.contains("Host key") || message.contains("fingerprint") || message.contains("ServerKeyVerifier")) {
            return new SshOperationException(SshResultCode.HOST_KEY_REJECTED,
                    "SSH 服务端主机指纹校验未通过 [" + serverInfo.toHostPortKey() + "]: " + message, e);
        }
        return new SshOperationException(SshResultCode.CONNECT_FAILED,
                "SSH 握手建连失败 [" + serverInfo.toHostPortKey() + "]: " + message, e);
    }

    /**
     * 池化会话内部受管持有者
     */
    @Getter
    private static class PooledSessionHolder {

        private static final AtomicLong ACCESS_SEQUENCE = new AtomicLong();

        private final String poolKey;
        private final SshServerInfo serverInfo;
        private final ClientSession session;
        private final long creationTime;
        private volatile long lastAccessTime;
        private volatile long accessOrder;

        PooledSessionHolder(String poolKey, SshServerInfo serverInfo, ClientSession session) {
            this.poolKey = poolKey;
            this.serverInfo = serverInfo;
            this.session = session;
            this.creationTime = System.currentTimeMillis();
            this.lastAccessTime = this.creationTime;
            this.accessOrder = ACCESS_SEQUENCE.incrementAndGet();
        }

        boolean isAlive() {
            return session != null && session.isOpen() && session.isAuthenticated()
                    && !session.isClosing() && !session.isClosed();
        }

        boolean isExpired(long idleTimeoutMillis) {
            return idleTimeoutMillis > 0 && (System.currentTimeMillis() - lastAccessTime) > idleTimeoutMillis;
        }

        void touch() {
            this.lastAccessTime = System.currentTimeMillis();
            this.accessOrder = ACCESS_SEQUENCE.incrementAndGet();
        }

        void close() {
            try {
                if (session != null && session.isOpen()) {
                    session.close(true).await(Duration.ofSeconds(1));
                }
            } catch (Exception e) {
                log.debug("ateng-boot-ssh: 关闭池化底层会话完成: {}", e.getMessage());
            }
        }
    }
}
