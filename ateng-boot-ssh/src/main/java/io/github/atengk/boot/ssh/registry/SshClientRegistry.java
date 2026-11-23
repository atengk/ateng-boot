package io.github.atengk.boot.ssh.registry;

import io.github.atengk.boot.ssh.exception.SshOperationException;
import io.github.atengk.boot.ssh.exception.SshResultCode;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import io.github.atengk.boot.ssh.properties.SshProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * SSH 多服务节点命名受管注册表
 * <p>
 * 集中纳管系统中所有由 YAML 声明或动态编程式注册的具名 SSH 节点配置描述符，
 * 支持按名称路由寻址、默认主节点优雅回退与只读元数据视图暴露。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
public class SshClientRegistry {

    /**
     * 默认主节点标识符
     */
    public static final String DEFAULT_SERVER = "default";

    private final Map<String, SshServerInfo> servers = new ConcurrentHashMap<>();
    private final Map<String, String> jumpHostRefs = new ConcurrentHashMap<>();
    private final List<Consumer<SshServerInfo>> evictionListeners = new CopyOnWriteArrayList<>();
    private final ReentrantLock topologyLock = new ReentrantLock();

    /**
     * 注册节点变更或注销时的会话驱逐回调监听器
     *
     * @param listener 旧节点描述符消费器（例如 {@code sessionPool::evict}）
     */
    public void addEvictionListener(Consumer<SshServerInfo> listener) {
        if (listener != null) {
            evictionListeners.add(listener);
        }
    }

    /**
     * 移除已注册的会话驱逐回调监听器
     *
     * @param listener 待移除的监听器
     * @return true 若移除成功
     */
    public boolean removeEvictionListener(Consumer<SshServerInfo> listener) {
        return listener != null && evictionListeners.remove(listener);
    }

    /**
     * 注册具名 SSH 服务节点描述符
     *
     * @param name       节点名称
     * @param serverInfo 节点目标连接描述符
     */
    public void register(String name, SshServerInfo serverInfo) {
        Assert.hasText(name, "server name must not be blank");
        Assert.notNull(serverInfo, "SshServerInfo must not be null");
        String trimmedName = name.trim();
        List<SshServerInfo> evicted = new ArrayList<>();
        topologyLock.lock();
        try {
            registerInternal(trimmedName, serverInfo, null, evicted);
            cascadeUpdateDownstream(trimmedName, serverInfo, evicted);
        } finally {
            if (topologyLock.isHeldByCurrentThread()) {
                topologyLock.unlock();
            }
        }
        evicted.forEach(this::notifyEviction);
    }

    /**
     * 注销指定具名 SSH 服务节点并触发关联旧会话驱逐
     *
     * @param name 节点名称
     * @return 被注销的节点描述符，若不存在或参数为空白则返回 null
     */
    public SshServerInfo unregister(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String trimmedName = name.trim();
        SshServerInfo removed;
        List<SshServerInfo> evicted = new ArrayList<>();
        topologyLock.lock();
        try {
            removed = servers.remove(trimmedName);
            jumpHostRefs.remove(trimmedName);
            if (removed != null) {
                log.info("ateng-boot-ssh: 已注销 SSH 服务节点 [name={}, target={}]", trimmedName, removed.toSessionKey());
                evicted.add(removed);
            }
        } finally {
            if (topologyLock.isHeldByCurrentThread()) {
                topologyLock.unlock();
            }
        }
        evicted.forEach(this::notifyEviction);
        return removed;
    }

    /**
     * 从配置属性对象转换并注册具名 SSH 服务节点（支持解析已注册的 jump-host 节点引用）
     *
     * @param name       节点名称
     * @param properties 配置属性
     */
    public void register(String name, SshProperties properties) {
        Assert.hasText(name, "server name must not be blank");
        Assert.notNull(properties, "SshProperties must not be null");
        String trimmedName = name.trim();
        List<SshServerInfo> evicted = new ArrayList<>();
        topologyLock.lock();
        try {
            SshServerInfo jumpInfo = null;
            String trimmedJump = null;
            String jumpHost = properties.getJumpHost();
            if (jumpHost != null && !jumpHost.isBlank()) {
                trimmedJump = jumpHost.trim();
                validateNoCycleOnLink(trimmedName, trimmedJump);
                jumpInfo = servers.get(trimmedJump);
                if (jumpInfo == null) {
                    throw missingJumpHostException(trimmedName, trimmedJump);
                }
            }
            SshServerInfo serverInfo = SshServerInfo.fromProperties(properties, jumpInfo);
            registerInternal(trimmedName, serverInfo, trimmedJump, evicted);
            cascadeUpdateDownstream(trimmedName, serverInfo, evicted);
        } finally {
            if (topologyLock.isHeldByCurrentThread()) {
                topologyLock.unlock();
            }
        }
        evicted.forEach(this::notifyEviction);
    }

    /**
     * 批量解析并注册根配置及命名多节点拓扑（支持多级 jump-host 级联与环路 Fail-Fast 检测）
     *
     * @param rootProperties SSH 全局配置属性
     */
    public void registerProperties(SshProperties rootProperties) {
        if (rootProperties == null) {
            return;
        }
        Map<String, SshProperties> nodeMap = new LinkedHashMap<>();
        if (rootProperties.getHost() != null && !rootProperties.getHost().isBlank()) {
            nodeMap.put(DEFAULT_SERVER, rootProperties);
        }
        if (rootProperties.getServers() != null && !rootProperties.getServers().isEmpty()) {
            rootProperties.getServers().forEach((name, serverProps) -> {
                if (name != null && !name.isBlank() && serverProps != null) {
                    nodeMap.put(name.trim(), serverProps);
                }
            });
        }

        if (nodeMap.isEmpty()) {
            return;
        }

        Map<String, SshServerInfo> resolved = new LinkedHashMap<>(nodeMap.size());
        Map<String, String> resolvedJumpNames = new LinkedHashMap<>(nodeMap.size());
        Set<String> visiting = new LinkedHashSet<>();
        for (String nodeName : nodeMap.keySet()) {
            resolveNodeTopology(nodeName, nodeMap, resolved, resolvedJumpNames, visiting);
        }

        // 全量拓扑校验无误后再原子写入注册表并级联刷新，防止局部残留
        List<SshServerInfo> evicted = new ArrayList<>();
        topologyLock.lock();
        try {
            resolved.forEach((nodeName, info) ->
                    registerInternal(nodeName, info, resolvedJumpNames.get(nodeName), evicted));
            resolved.forEach((nodeName, info) ->
                    cascadeUpdateDownstream(nodeName, info, evicted));
        } finally {
            if (topologyLock.isHeldByCurrentThread()) {
                topologyLock.unlock();
            }
        }
        evicted.forEach(this::notifyEviction);
    }

    private void registerInternal(String trimmedName,
                                  SshServerInfo serverInfo,
                                  String jumpHostName,
                                  List<SshServerInfo> evicted) {
        if (jumpHostName != null && !jumpHostName.isBlank()) {
            jumpHostRefs.put(trimmedName, jumpHostName.trim());
        } else {
            jumpHostRefs.remove(trimmedName);
        }
        SshServerInfo previous = servers.put(trimmedName, serverInfo);
        log.info("ateng-boot-ssh: 成功注册 SSH 服务节点 [name={}, target={}]", trimmedName, serverInfo.toSessionKey());
        if (previous != null && (!Objects.equals(previous.toPoolKey(), serverInfo.toPoolKey()) || !Objects.equals(previous, serverInfo))) {
            evicted.add(previous);
        }
    }

    private void validateNoCycleOnLink(String nodeName, String jumpHostName) {
        List<String> chain = new ArrayList<>();
        chain.add(nodeName);
        String cursor = jumpHostName;
        Set<String> visited = new LinkedHashSet<>();
        while (cursor != null && !cursor.isBlank()) {
            chain.add(cursor);
            if (cursor.equals(nodeName) || !visited.add(cursor)) {
                throw new SshOperationException(SshResultCode.JUMP_HOST_FAILED,
                        "检测到 SSH 跳板机 (jump-host) 循环引用拓扑: [" + String.join(" -> ", chain) + "]");
            }
            cursor = jumpHostRefs.get(cursor);
        }
    }

    private void cascadeUpdateDownstream(String rootParentName,
                                         SshServerInfo rootParentInfo,
                                         List<SshServerInfo> evicted) {
        Queue<String> queue = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>();
        queue.add(rootParentName);
        visited.add(rootParentName);

        while (!queue.isEmpty()) {
            String currentParent = queue.poll();
            SshServerInfo parentInfo = currentParent.equals(rootParentName) ? rootParentInfo : servers.get(currentParent);
            if (parentInfo == null) {
                continue;
            }
            for (Map.Entry<String, String> entry : jumpHostRefs.entrySet()) {
                String childName = entry.getKey();
                if (!currentParent.equals(entry.getValue()) || !visited.add(childName)) {
                    continue;
                }
                SshServerInfo oldChild = servers.get(childName);
                if (oldChild == null) {
                    continue;
                }
                SshServerInfo updatedChild = oldChild.toBuilder().jumpHost(parentInfo).build();
                if (!Objects.equals(oldChild.toPoolKey(), updatedChild.toPoolKey()) || !Objects.equals(oldChild, updatedChild)) {
                    servers.put(childName, updatedChild);
                    evicted.add(oldChild);
                    log.info("ateng-boot-ssh: 级联刷新下游 SSH 节点跳板机快照 [node={}, jumpHost={}]",
                            childName, currentParent);
                }
                queue.add(childName);
            }
        }
    }

    private SshServerInfo resolveNodeTopology(String nodeName,
                                              Map<String, SshProperties> nodeMap,
                                              Map<String, SshServerInfo> resolved,
                                              Map<String, String> resolvedJumpNames,
                                              Set<String> visiting) {
        SshServerInfo cached = resolved.get(nodeName);
        if (cached != null) {
            return cached;
        }

        SshProperties props = nodeMap.get(nodeName);
        if (props == null) {
            throw new SshOperationException(SshResultCode.JUMP_HOST_FAILED,
                    "SSH 跳板机拓扑中引用的节点不存在: [" + nodeName + "]");
        }

        if (!visiting.add(nodeName)) {
            String chain = String.join(" -> ", visiting) + " -> " + nodeName;
            throw new SshOperationException(SshResultCode.JUMP_HOST_FAILED,
                    "检测到 SSH 跳板机 (jump-host) 循环引用拓扑: [" + chain + "]");
        }

        SshServerInfo jumpInfo = null;
        String jumpHostName = props.getJumpHost();
        if (jumpHostName != null && !jumpHostName.isBlank()) {
            String trimmedJump = jumpHostName.trim();
            if (!nodeMap.containsKey(trimmedJump)) {
                throw missingJumpHostException(nodeName, trimmedJump);
            }
            resolvedJumpNames.put(nodeName, trimmedJump);
            jumpInfo = resolveNodeTopology(trimmedJump, nodeMap, resolved, resolvedJumpNames, visiting);
        }

        visiting.remove(nodeName);
        SshServerInfo info = SshServerInfo.fromProperties(props, jumpInfo);
        resolved.put(nodeName, info);
        return info;
    }

    private static SshOperationException missingJumpHostException(String nodeName, String jumpHostName) {
        return new SshOperationException(SshResultCode.JUMP_HOST_FAILED,
                "节点 [" + nodeName + "] 引用的跳板机节点不存在: [" + jumpHostName + "]");
    }

    /**
     * 根据节点名称获取对应的连接描述符
     * <p>
     * 若名称为空或空白，则自动降级返回 {@code default} 主节点。
     * </p>
     *
     * @param name 节点名称
     * @return 对应的 SshServerInfo 描述符
     * @throws SshOperationException 若指定了非空名称但在注册表中不存在
     */
    public SshServerInfo getServer(String name) {
        if (name == null || name.isBlank()) {
            return getDefaultServer();
        }
        SshServerInfo serverInfo = servers.get(name.trim());
        if (serverInfo == null) {
            throw new SshOperationException(SshResultCode.SERVER_NOT_FOUND, "未找到指定的 SSH 服务节点: " + name);
        }
        return serverInfo;
    }

    /**
     * 获取默认主节点的连接描述符
     *
     * @return 默认主节点描述符
     * @throws SshOperationException 若注册表中没有任何可用节点
     */
    public SshServerInfo getDefaultServer() {
        SshServerInfo defaultServer = servers.get(DEFAULT_SERVER);
        if (defaultServer == null) {
            // 保底兜底：若无显式 default，取注册表中第一个节点
            if (!servers.isEmpty()) {
                return servers.values().iterator().next();
            }
            throw new SshOperationException(SshResultCode.SERVER_NOT_FOUND, "SSH 注册表中未配置任何可用服务节点");
        }
        return defaultServer;
    }

    /**
     * 判断是否包含指定服务节点
     *
     * @param name 节点名称
     * @return 存在返回 true
     */
    public boolean contains(String name) {
        return name != null && servers.containsKey(name.trim());
    }

    /**
     * 获取所有已注册的节点名称只读集合
     *
     * @return 节点名称集合
     */
    public Set<String> getServerNames() {
        return Collections.unmodifiableSet(servers.keySet());
    }

    /**
     * 获取所有已注册的节点映射只读视图
     *
     * @return 节点映射表
     */
    public Map<String, SshServerInfo> getServers() {
        return Collections.unmodifiableMap(servers);
    }

    /**
     * 清理所有已注册的节点信息并逐一通知驱逐关联会话
     */
    public void clear() {
        List<SshServerInfo> evicted = new ArrayList<>();
        topologyLock.lock();
        try {
            for (String key : new ArrayList<>(servers.keySet())) {
                SshServerInfo removed = servers.remove(key);
                if (removed != null) {
                    evicted.add(removed);
                }
            }
            jumpHostRefs.clear();
        } finally {
            if (topologyLock.isHeldByCurrentThread()) {
                topologyLock.unlock();
            }
        }
        evicted.forEach(this::notifyEviction);
    }

    private void notifyEviction(SshServerInfo serverInfo) {
        if (serverInfo == null) {
            return;
        }
        for (Consumer<SshServerInfo> listener : evictionListeners) {
            try {
                listener.accept(serverInfo);
            } catch (Exception e) {
                log.warn("ateng-boot-ssh: 执行注册表驱逐回调异常 [{}]: {}", serverInfo.toSessionKey(), e.getMessage());
            }
        }
    }
}
