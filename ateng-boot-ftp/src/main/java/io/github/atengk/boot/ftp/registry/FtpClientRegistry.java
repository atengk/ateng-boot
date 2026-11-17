package io.github.atengk.boot.ftp.registry;

import io.github.atengk.boot.ftp.customizer.FtpClientCustomizer;
import io.github.atengk.boot.ftp.exception.FtpException;
import io.github.atengk.boot.ftp.exception.FtpResultCode;
import io.github.atengk.boot.ftp.model.FtpPoolStats;
import io.github.atengk.boot.ftp.pool.FtpClientPooledObjectFactory;
import io.github.atengk.boot.ftp.properties.FtpPoolProperties;
import io.github.atengk.boot.ftp.properties.FtpProperties;
import io.github.atengk.boot.ftp.template.FtpTemplate;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.pool2.ObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * FTP 多服务节点受管注册表
 * <p>
 * 集中纳管系统内所有具名 FTP/FTPS 节点实例的连接池与操作门面，
 * 支持动态注册、同名平滑热替换、对称安全注销、按名称寻址、默认主节点回退、
 * 连接池运行指标快照查询与确定性容器生命周期资源销毁。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
public class FtpClientRegistry implements AutoCloseable {

    /**
     * 默认主节点标识符
     */
    public static final String DEFAULT_SERVER = "default";

    private record RegisteredNode(FtpTemplate template, ObjectPool<FTPClient> pool) {
    }

    private final Map<String, RegisteredNode> nodes = new ConcurrentHashMap<>();
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final FtpProperties defaultProperties;
    private final List<FtpClientCustomizer> customizers;

    /**
     * 默认无参构造器
     */
    public FtpClientRegistry() {
        this(null, Collections.emptyList());
    }

    /**
     * 含全局默认配置与客户端定制器扩展链的构造器
     *
     * @param defaultProperties 全局默认配置
     * @param customizers       客户端定制器列表
     */
    public FtpClientRegistry(FtpProperties defaultProperties, List<FtpClientCustomizer> customizers) {
        this.defaultProperties = defaultProperties;
        this.customizers = customizers != null ? List.copyOf(customizers) : Collections.emptyList();
    }

    /**
     * 注册具名 FTP 服务节点（若同名节点已存在，将先安全关闭旧连接池并平滑热替换）
     *
     * @param name     节点名称
     * @param template 节点专职操作门面
     * @param pool     节点底层连接池
     */
    public void register(String name, FtpTemplate template, ObjectPool<FTPClient> pool) {
        Assert.hasText(name, "server name must not be blank");
        Assert.notNull(template, "FtpTemplate must not be null");
        Assert.notNull(pool, "ObjectPool must not be null");
        String normalizedName = normalizeServerName(name);

        RegisteredNode previous;
        lifecycleLock.lock();
        try {
            previous = nodes.put(normalizedName, new RegisteredNode(template, pool));
        } finally {
            lifecycleLock.unlock();
        }

        if (previous != null && previous.pool() != pool) {
            closePoolQuietly(normalizedName, previous.pool(), "热替换旧连接池");
        }
        log.info("ateng-boot-ftp: 成功注册 FTP 服务节点 [name={}]", normalizedName);
    }

    /**
     * 基于节点配置属性动态构建受管连接池并注册具名 FTP 服务节点
     *
     * @param name           节点名称
     * @param nodeProperties 节点配置属性
     * @return 构建并注册成功的 FtpTemplate 操作门面
     */
    public FtpTemplate register(String name, FtpProperties nodeProperties) {
        Assert.hasText(name, "server name must not be blank");
        Assert.notNull(nodeProperties, "FtpProperties must not be null");
        String normalizedName = normalizeServerName(name);
        FtpProperties effectiveProps = nodeProperties.copy();
        if (defaultProperties != null && !DEFAULT_SERVER.equals(normalizedName)) {
            effectiveProps.mergeFrom(defaultProperties);
        }
        ObjectPool<FTPClient> pool = createObjectPool(normalizedName, effectiveProps);
        FtpTemplate template = new FtpTemplate(pool, effectiveProps, this, normalizedName);
        register(normalizedName, template, pool);
        return template;
    }

    /**
     * 对称注销指定具名 FTP 服务节点并确定性关闭其底层连接池
     * <p>
     * 内置默认主节点防空悬守卫：严禁注销 {@code default} 主节点。
     * </p>
     *
     * @param name 待注销的服务节点名称
     * @return 节点存在并成功注销关闭返回 true，节点原本不存在返回 false
     * @throws FtpException 当尝试注销 default 主节点时抛出 OPERATION_FAILED 异常
     */
    public boolean unregister(String name) {
        Assert.hasText(name, "server name must not be blank");
        String normalizedName = normalizeServerName(name);
        if (DEFAULT_SERVER.equals(normalizedName)) {
            throw new FtpException(FtpResultCode.OPERATION_FAILED, "严禁注销 default 默认主节点");
        }

        RegisteredNode removed;
        lifecycleLock.lock();
        try {
            removed = nodes.remove(normalizedName);
        } finally {
            lifecycleLock.unlock();
        }

        if (removed == null) {
            return false;
        }
        closePoolQuietly(normalizedName, removed.pool(), "注销节点连接池");
        log.info("ateng-boot-ftp: 已安全注销并关闭 FTP 服务节点 [name={}]", normalizedName);
        return true;
    }

    /**
     * 根据节点名称获取对应的操作门面（若为空或空白则降级返回 default 主节点）
     *
     * @param name 节点名称
     * @return 对应的 FtpTemplate 操作门面
     * @throws FtpException 若指定了非空名称但在注册表中不存在则抛出 SERVER_NOT_FOUND
     */
    public FtpTemplate getTemplate(String name) {
        if (name == null || name.isBlank()) {
            return getDefaultTemplate();
        }
        return getNodeOrThrow(name).template();
    }

    /**
     * 获取默认主节点的操作门面
     *
     * @return 默认主节点 FtpTemplate
     */
    public FtpTemplate getDefaultTemplate() {
        RegisteredNode defaultNode = nodes.get(DEFAULT_SERVER);
        if (defaultNode == null) {
            // 保底兜底：若无显式 default，取注册表中第一个节点
            if (!nodes.isEmpty()) {
                return nodes.values().iterator().next().template();
            }
            throw new FtpException(FtpResultCode.SERVER_NOT_FOUND, "FTP 注册表中未配置任何可用服务节点");
        }
        return defaultNode.template();
    }

    /**
     * 获取指定节点的底层原生连接池
     *
     * @param name 节点名称
     * @return 原生连接池
     */
    public ObjectPool<FTPClient> getPool(String name) {
        if (name == null || name.isBlank()) {
            return getDefaultPool();
        }
        return getNodeOrThrow(name).pool();
    }

    /**
     * 获取默认主节点的底层原生连接池
     *
     * @return 默认原生连接池
     */
    public ObjectPool<FTPClient> getDefaultPool() {
        RegisteredNode defaultNode = nodes.get(DEFAULT_SERVER);
        if (defaultNode == null) {
            if (!nodes.isEmpty()) {
                return nodes.values().iterator().next().pool();
            }
            throw new FtpException(FtpResultCode.SERVER_NOT_FOUND, "FTP 注册表中未配置任何可用服务节点连接池");
        }
        return defaultNode.pool();
    }

    /**
     * 判断是否包含指定服务节点
     *
     * @param name 节点名称
     * @return 存在返回 true
     */
    public boolean contains(String name) {
        return name != null && !name.isBlank() && nodes.containsKey(normalizeServerName(name));
    }

    /**
     * 获取所有已注册的节点名称只读集合
     *
     * @return 节点名称集合
     */
    public Set<String> getServerNames() {
        return Collections.unmodifiableSet(nodes.keySet());
    }

    /**
     * 获取默认主节点的受管连接池运行时指标快照
     *
     * @return 默认主节点连接池运行指标快照
     */
    public FtpPoolStats getPoolStats() {
        return getPoolStats(DEFAULT_SERVER);
    }

    /**
     * 获取指定服务节点的受管连接池运行时指标快照
     *
     * @param name 节点名称（为空或空白则查询 default 主节点）
     * @return 连接池运行指标快照
     */
    public FtpPoolStats getPoolStats(String name) {
        String targetName = (name == null || name.isBlank()) ? DEFAULT_SERVER : normalizeServerName(name);
        ObjectPool<FTPClient> pool = getPool(targetName);
        return buildPoolStats(targetName, pool);
    }

    /**
     * 获取所有已注册服务节点的受管连接池运行时指标快照映射
     *
     * @return 节点名到指标快照的只读映射
     */
    public Map<String, FtpPoolStats> getAllPoolStats() {
        Map<String, FtpPoolStats> statsMap = new LinkedHashMap<>();
        for (Map.Entry<String, RegisteredNode> entry : nodes.entrySet()) {
            statsMap.put(entry.getKey(), buildPoolStats(entry.getKey(), entry.getValue().pool()));
        }
        return Collections.unmodifiableMap(statsMap);
    }

    /**
     * 基于节点名与底层连接池构建不可变指标快照
     *
     * @param serverName 节点名称
     * @param pool       底层连接池
     * @return 连接池运行指标快照
     */
    public static FtpPoolStats buildPoolStats(String serverName, ObjectPool<FTPClient> pool) {
        Assert.hasText(serverName, "serverName must not be blank");
        Assert.notNull(pool, "ObjectPool must not be null");
        int waiting = 0;
        int maxTotal = -1;
        int maxIdle = -1;
        int minIdle = -1;
        if (pool instanceof GenericObjectPool<?> genericPool) {
            waiting = genericPool.getNumWaiters();
            maxTotal = genericPool.getMaxTotal();
            maxIdle = genericPool.getMaxIdle();
            minIdle = genericPool.getMinIdle();
        }
        return FtpPoolStats.builder()
                .serverName(serverName.trim())
                .activeConnections(pool.getNumActive())
                .idleConnections(pool.getNumIdle())
                .waitingBorrowers(waiting)
                .maxTotal(maxTotal)
                .maxIdle(maxIdle)
                .minIdle(minIdle)
                .build();
    }

    /**
     * 基于节点配置构建受管连接池
     *
     * @param serverName 服务节点名称
     * @param props      节点配置
     * @return Commons Pool2 受管对象池
     */
    public ObjectPool<FTPClient> createObjectPool(String serverName, FtpProperties props) {
        Assert.hasText(serverName, "serverName must not be blank");
        Assert.notNull(props, "FtpProperties must not be null");
        FtpClientPooledObjectFactory factory = new FtpClientPooledObjectFactory(serverName, props, customizers);
        GenericObjectPoolConfig<FTPClient> config = new GenericObjectPoolConfig<>();
        FtpPoolProperties poolProps = props.getPool() != null ? props.getPool() : new FtpPoolProperties();

        config.setMaxTotal(poolProps.getMaxTotal());
        config.setMaxIdle(poolProps.getMaxIdle());
        config.setMinIdle(poolProps.getMinIdle());
        config.setMaxWait(poolProps.getMaxWait());
        config.setTestOnBorrow(poolProps.isTestOnBorrow());
        config.setTestOnReturn(poolProps.isTestOnReturn());
        config.setTestWhileIdle(poolProps.isTestWhileIdle());
        config.setTimeBetweenEvictionRuns(poolProps.getTimeBetweenEvictionRuns());
        config.setMinEvictableIdleDuration(poolProps.getMinEvictableIdleTime());

        return new GenericObjectPool<>(factory, config);
    }

    /**
     * 容器停机销毁回调：安全释放与关闭所有受管连接池物理连接
     */
    @Override
    public void close() {
        log.info("ateng-boot-ftp: 正在释放并关闭所有已注册的 FTP 服务节点连接池...");
        List<Map.Entry<String, RegisteredNode>> snapshot;
        lifecycleLock.lock();
        try {
            snapshot = new ArrayList<>(nodes.entrySet());
            nodes.clear();
        } finally {
            lifecycleLock.unlock();
        }
        for (Map.Entry<String, RegisteredNode> entry : snapshot) {
            closePoolQuietly(entry.getKey(), entry.getValue().pool(), "关闭节点连接池");
        }
    }

    private RegisteredNode getNodeOrThrow(String name) {
        RegisteredNode node = nodes.get(normalizeServerName(name));
        if (node == null) {
            throw new FtpException(FtpResultCode.SERVER_NOT_FOUND, "未找到指定的 FTP 服务节点: " + name);
        }
        return node;
    }

    private String normalizeServerName(String name) {
        String trimmed = name.trim();
        return DEFAULT_SERVER.equalsIgnoreCase(trimmed) ? DEFAULT_SERVER : trimmed;
    }

    private void closePoolQuietly(String name, ObjectPool<FTPClient> pool, String action) {
        try {
            pool.close();
            log.debug("ateng-boot-ftp: {}成功 [name={}]", action, name);
        } catch (Exception e) {
            log.warn("ateng-boot-ftp: {}失败 [name={}]: {}", action, name, e.getMessage());
        }
    }
}
