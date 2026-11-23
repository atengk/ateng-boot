package io.github.atengk.boot.tcp.template;

import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.tcp.cluster.TcpClusterBridge;
import io.github.atengk.boot.tcp.correlation.TcpRequestCorrelationManager;
import io.github.atengk.boot.tcp.enums.CloseReason;
import io.github.atengk.boot.tcp.exception.TcpException;
import io.github.atengk.boot.tcp.exception.TcpTimeoutException;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * TCP 服务端核心操作门面
 * <p>
 * 面向服务端业务场景，提供单播（指定会话或设备）、全局广播、分组组播以及全双工“请求-响应 (Request-Response)”RPC 闭环。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class TcpServerTemplate {

    private static final Logger log = LoggerFactory.getLogger(TcpServerTemplate.class);

    private final TcpSessionManager sessionManager;
    private final TcpRequestCorrelationManager correlationManager;
    private final TcpClusterBridge clusterBridge;

    public TcpServerTemplate(TcpSessionManager sessionManager) {
        this(sessionManager, null, null);
    }

    public TcpServerTemplate(TcpSessionManager sessionManager, TcpRequestCorrelationManager correlationManager) {
        this(sessionManager, correlationManager, null);
    }

    public TcpServerTemplate(TcpSessionManager sessionManager,
                             TcpRequestCorrelationManager correlationManager,
                             TcpClusterBridge clusterBridge) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager 不能为空");
        this.correlationManager = correlationManager;
        this.clusterBridge = clusterBridge;
    }

    /**
     * 向指定会话发送消息
     *
     * @param sessionId 会话唯一标识
     * @param payload   报文载荷（支持 TcpPacket, TcpPayload, byte[], String 或任意 POJO）
     */
    public void send(String sessionId, Object payload) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        TcpSession session = sessionManager.getSession(sessionId);
        if (session == null || !session.isActive()) {
            throw new IllegalStateException("未找到对应的活跃 TCP 会话: sessionId=" + sessionId);
        }
        Object msg = wrapPayload(payload);
        session.send(msg);
    }

    /**
     * 向指定业务设备标识发送消息
     *
     * @param deviceId 业务设备唯一编号
     * @param payload  报文载荷
     */
    public void sendToDevice(String deviceId, Object payload) {
        if (deviceId == null) {
            throw new IllegalArgumentException("deviceId 不能为空");
        }
        TcpSession session = sessionManager.getSessionByDeviceId(deviceId);
        if (session == null || !session.isActive()) {
            throw new IllegalStateException("设备未在线或连接已断开: deviceId=" + deviceId);
        }
        Object msg = wrapPayload(payload);
        session.send(msg);
    }

    /**
     * 向指定会话发起全双工同步请求并等待对端应答（默认 10s 超时）
     *
     * @param sessionId 会话唯一标识
     * @param payload   报文载荷
     * @return 响应报文
     */
    public TcpPacket request(String sessionId, Object payload) {
        return request(sessionId, payload, Duration.ofSeconds(10));
    }

    /**
     * 向指定会话发起全双工同步请求并在指定超时窗口内等待对端应答
     *
     * @param sessionId 会话唯一标识
     * @param payload   报文载荷
     * @param timeout   超时时长
     * @return 响应报文
     */
    public TcpPacket request(String sessionId, Object payload, Duration timeout) {
        Objects.requireNonNull(timeout, "timeout 不能为空");
        CompletableFuture<TcpPacket> future = requestAsync(sessionId, payload, timeout);
        try {
            return future.get(timeout.toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new TcpException("TCP RPC 请求等待中断: " + ie.getMessage(), ie);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            if (cause instanceof TcpException te) {
                throw te;
            }
            throw new TcpException("TCP RPC 请求执行异常: " + cause.getMessage(), cause);
        } catch (TimeoutException te) {
            throw new TcpTimeoutException("TCP 请求调用超时: timeout=" + timeout, te);
        }
    }

    /**
     * 向指定会话发起全双工同步请求并等待强类型对端应答（默认 10s 超时）
     *
     * @param sessionId    会话唯一标识
     * @param payload      报文载荷
     * @param responseType 响应目标类型
     * @param <T>          响应强类型
     * @return 对端响应对象
     */
    public <T> T request(String sessionId, Object payload, Class<T> responseType) {
        return request(sessionId, payload, Duration.ofSeconds(10), responseType);
    }

    /**
     * 向指定会话发起全双工同步请求并在指定超时窗口内等待强类型对端应答
     *
     * @param sessionId    会话唯一标识
     * @param payload      报文载荷
     * @param timeout      超时时长
     * @param responseType 响应目标类型
     * @param <T>          响应强类型
     * @return 对端响应对象
     */
    public <T> T request(String sessionId, Object payload, Duration timeout, Class<T> responseType) {
        Objects.requireNonNull(responseType, "responseType 不能为空");
        TcpPacket responsePacket = request(sessionId, payload, timeout);
        return TcpClientTemplate.castResponse(responsePacket, responseType);
    }

    /**
     * 向指定会话发起全双工异步 RPC 请求（默认 10s 超时）
     *
     * @param sessionId 会话唯一标识
     * @param payload   报文载荷
     * @return 响应 Future
     */
    public CompletableFuture<TcpPacket> requestAsync(String sessionId, Object payload) {
        return requestAsync(sessionId, payload, Duration.ofSeconds(10));
    }

    /**
     * 向指定会话发起全双工异步 RPC 请求并在指定超时窗口内等待响应
     *
     * @param sessionId 会话唯一标识
     * @param payload   报文载荷
     * @param timeout   超时时长
     * @return 响应 Future
     */
    public CompletableFuture<TcpPacket> requestAsync(String sessionId, Object payload, Duration timeout) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        Objects.requireNonNull(timeout, "timeout 不能为空");
        if (correlationManager == null) {
            throw new IllegalStateException("未配置 TcpRequestCorrelationManager，无法发起全双工 RPC 请求");
        }

        TcpSession session = sessionManager.getSession(sessionId);
        if (session == null || !session.isActive()) {
            throw new IllegalStateException("未找到对应的活跃 TCP 会话: sessionId=" + sessionId);
        }

        TcpPacket packet = toTcpPacket(payload);
        String requestId = packet.getRequestId();

        CompletableFuture<TcpPacket> future = correlationManager.register(requestId, timeout);

        try {
            session.send(packet).addListener(f -> {
                if (!f.isSuccess()) {
                    correlationManager.completeExceptionally(requestId, f.cause());
                }
            });
        } catch (Exception ex) {
            correlationManager.completeExceptionally(requestId, ex);
            throw ex;
        }

        return future;
    }

    /**
     * 向指定会话发起全双工异步强类型 RPC 请求（默认 10s 超时）
     *
     * @param sessionId    会话唯一标识
     * @param payload      报文载荷
     * @param responseType 响应目标类型
     * @param <T>          响应强类型
     * @return 强类型异步响应 Future
     */
    public <T> CompletableFuture<T> requestAsync(String sessionId, Object payload, Class<T> responseType) {
        return requestAsync(sessionId, payload, Duration.ofSeconds(10), responseType);
    }

    /**
     * 向指定会话发起全双工异步强类型 RPC 请求并在指定超时窗口内等待响应
     *
     * @param sessionId    会话唯一标识
     * @param payload      报文载荷
     * @param timeout      超时时长
     * @param responseType 响应目标类型
     * @param <T>          响应强类型
     * @return 强类型异步响应 Future
     */
    public <T> CompletableFuture<T> requestAsync(String sessionId, Object payload, Duration timeout, Class<T> responseType) {
        Objects.requireNonNull(responseType, "responseType 不能为空");
        return requestAsync(sessionId, payload, timeout)
                .thenApply(responsePacket -> TcpClientTemplate.castResponse(responsePacket, responseType));
    }

    /**
     * 向指定业务设备标识发起全双工同步请求并等待对端应答（默认 10s 超时）
     *
     * @param deviceId 业务设备唯一编号
     * @param payload  报文载荷
     * @return 响应报文
     */
    public TcpPacket requestToDevice(String deviceId, Object payload) {
        return requestToDevice(deviceId, payload, Duration.ofSeconds(10));
    }

    /**
     * 向指定业务设备标识发起全双工同步请求并在指定超时窗口内等待对端应答
     *
     * @param deviceId 业务设备唯一编号
     * @param payload  报文载荷
     * @param timeout  超时时长
     * @return 响应报文
     */
    public TcpPacket requestToDevice(String deviceId, Object payload, Duration timeout) {
        Objects.requireNonNull(timeout, "timeout 不能为空");
        CompletableFuture<TcpPacket> future = requestToDeviceAsync(deviceId, payload, timeout);
        try {
            return future.get(timeout.toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new TcpException("TCP RPC 请求等待中断: " + ie.getMessage(), ie);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            if (cause instanceof TcpException te) {
                throw te;
            }
            throw new TcpException("TCP RPC 请求执行异常: " + cause.getMessage(), cause);
        } catch (TimeoutException te) {
            throw new TcpTimeoutException("TCP 请求调用超时: timeout=" + timeout, te);
        }
    }

    /**
     * 向指定业务设备标识发起全双工同步请求并等待强类型对端应答（默认 10s 超时）
     *
     * @param deviceId     业务设备唯一编号
     * @param payload      报文载荷
     * @param responseType 响应目标类型
     * @param <T>          响应强类型
     * @return 对端响应对象
     */
    public <T> T requestToDevice(String deviceId, Object payload, Class<T> responseType) {
        return requestToDevice(deviceId, payload, Duration.ofSeconds(10), responseType);
    }

    /**
     * 向指定业务设备标识发起全双工同步请求并在指定超时窗口内等待强类型对端应答
     *
     * @param deviceId     业务设备唯一编号
     * @param payload      报文载荷
     * @param timeout      超时时长
     * @param responseType 响应目标类型
     * @param <T>          响应强类型
     * @return 对端响应对象
     */
    public <T> T requestToDevice(String deviceId, Object payload, Duration timeout, Class<T> responseType) {
        Objects.requireNonNull(responseType, "responseType 不能为空");
        TcpPacket responsePacket = requestToDevice(deviceId, payload, timeout);
        return TcpClientTemplate.castResponse(responsePacket, responseType);
    }

    /**
     * 向指定业务设备标识发起全双工异步 RPC 请求（默认 10s 超时）
     *
     * @param deviceId 业务设备唯一编号
     * @param payload  报文载荷
     * @return 响应 Future
     */
    public CompletableFuture<TcpPacket> requestToDeviceAsync(String deviceId, Object payload) {
        return requestToDeviceAsync(deviceId, payload, Duration.ofSeconds(10));
    }

    /**
     * 向指定业务设备标识发起全双工异步 RPC 请求并在指定超时窗口内等待响应
     *
     * @param deviceId 业务设备唯一编号
     * @param payload  报文载荷
     * @param timeout  超时时长
     * @return 响应 Future
     */
    public CompletableFuture<TcpPacket> requestToDeviceAsync(String deviceId, Object payload, Duration timeout) {
        if (deviceId == null) {
            throw new IllegalArgumentException("deviceId 不能为空");
        }
        TcpSession session = sessionManager.getSessionByDeviceId(deviceId);
        if (session == null || !session.isActive()) {
            throw new IllegalStateException("设备未在线或连接已断开: deviceId=" + deviceId);
        }
        return requestAsync(session.getSessionId(), payload, timeout);
    }

    /**
     * 向指定业务设备标识发起全双工异步强类型 RPC 请求（默认 10s 超时）
     *
     * @param deviceId     业务设备唯一编号
     * @param payload      报文载荷
     * @param responseType 响应目标类型
     * @param <T>          响应强类型
     * @return 强类型异步响应 Future
     */
    public <T> CompletableFuture<T> requestToDeviceAsync(String deviceId, Object payload, Class<T> responseType) {
        return requestToDeviceAsync(deviceId, payload, Duration.ofSeconds(10), responseType);
    }

    /**
     * 向指定业务设备标识发起全双工异步强类型 RPC 请求并在指定超时窗口内等待响应
     *
     * @param deviceId     业务设备唯一编号
     * @param payload      报文载荷
     * @param timeout      超时时长
     * @param responseType 响应目标类型
     * @param <T>          响应强类型
     * @return 强类型异步响应 Future
     */
    public <T> CompletableFuture<T> requestToDeviceAsync(String deviceId, Object payload, Duration timeout, Class<T> responseType) {
        Objects.requireNonNull(responseType, "responseType 不能为空");
        return requestToDeviceAsync(deviceId, payload, timeout)
                .thenApply(responsePacket -> TcpClientTemplate.castResponse(responsePacket, responseType));
    }

    /**
     * 向当前所有在线的活跃会话进行全局广播
     *
     * @param payload 报文载荷
     */
    public void broadcast(Object payload) {
        sendToSessions(sessionManager.getAllSessions(), payload, Collections.emptySet(), "全量广播");
    }

    /**
     * 向归属指定服务端端口（serverName）的所有在线活跃会话定向广播
     *
     * @param serverName 服务端实例名称（为空或空白时安全返回）
     * @param payload    报文载荷
     */
    public void broadcastToServer(String serverName, Object payload) {
        if (serverName == null || serverName.isBlank()) {
            return;
        }
        sendToSessions(sessionManager.getSessionsByServer(serverName), payload, Collections.emptySet(),
                "服务端端口 [" + serverName + "] 定向广播");
    }

    /**
     * 向当前所有在线的活跃会话进行全局排他广播（自动跳过被排除的会话集合）
     *
     * @param payload            报文载荷
     * @param excludedSessionIds 需跳过的会话 ID 集合
     */
    public void broadcastExcept(Object payload, Collection<String> excludedSessionIds) {
        sendToSessions(sessionManager.getAllSessions(), payload, normalizeExcludedIds(excludedSessionIds), "全局排他广播");
    }

    /**
     * 向指定业务逻辑分组的所有在线会话进行组播
     *
     * @param group   分组名称
     * @param payload 报文载荷
     */
    public void sendToGroup(String group, Object payload) {
        if (group == null || group.isBlank()) {
            return;
        }
        sendToSessions(sessionManager.getSessionsByGroup(group), payload, Collections.emptySet(), "分组 [" + group + "] 组播");
    }

    /**
     * 向指定业务逻辑分组的所有在线会话进行排他组播（自动跳过被排除的会话集合）
     *
     * @param group              分组名称
     * @param payload            报文载荷
     * @param excludedSessionIds 需跳过的会话 ID 集合
     */
    public void sendToGroupExcept(String group, Object payload, Collection<String> excludedSessionIds) {
        if (group == null || group.isBlank()) {
            return;
        }
        sendToSessions(sessionManager.getSessionsByGroup(group), payload,
                normalizeExcludedIds(excludedSessionIds), "分组 [" + group + "] 排他组播");
    }

    private void sendToSessions(Collection<TcpSession> sessions,
                                Object payload,
                                Set<String> excludedSessionIds,
                                String sceneLabel) {
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        Object msg = wrapPayload(payload);
        for (TcpSession session : sessions) {
            if (session == null || !session.isActive()) {
                continue;
            }
            if (!excludedSessionIds.isEmpty() && excludedSessionIds.contains(session.getSessionId())) {
                continue;
            }
            try {
                session.send(msg);
            } catch (Exception ex) {
                log.warn("向会话执行{}失败: sessionId={}", sceneLabel, session.getSessionId(), ex);
            }
        }
    }

    private static Set<String> normalizeExcludedIds(Collection<String> excludedSessionIds) {
        if (excludedSessionIds == null || excludedSessionIds.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> excluded = new HashSet<>(excludedSessionIds.size());
        for (String id : excludedSessionIds) {
            if (id != null && !id.isBlank()) {
                excluded.add(id);
            }
        }
        return excluded;
    }

    /**
     * 主动断开并注销指定会话连接（默认原因 NORMAL）
     *
     * @param sessionId 会话唯一标识
     */
    public void close(String sessionId) {
        close(sessionId, CloseReason.NORMAL);
    }

    /**
     * 主动断开并注销指定会话连接
     *
     * @param sessionId 会话唯一标识
     * @param reason    断开关闭原因
     */
    public void close(String sessionId, CloseReason reason) {
        if (sessionId == null) {
            return;
        }
        sessionManager.closeSession(sessionId, reason != null ? reason : CloseReason.NORMAL);
    }

    /**
     * 主动断开并注销指定业务设备绑定的会话（默认原因 NORMAL）
     *
     * @param deviceId 业务设备唯一编号
     */
    public void closeDevice(String deviceId) {
        closeDevice(deviceId, CloseReason.NORMAL);
    }

    /**
     * 主动断开并注销指定业务设备绑定的会话
     *
     * @param deviceId 业务设备唯一编号
     * @param reason   断开关闭原因
     */
    public void closeDevice(String deviceId, CloseReason reason) {
        if (deviceId == null) {
            return;
        }
        sessionManager.closeDevice(deviceId, reason != null ? reason : CloseReason.NORMAL);
    }

    /**
     * 获取当前在线活跃连接总数
     *
     * @return 活跃连接数
     */
    public int getActiveConnections() {
        return sessionManager.getActiveCount();
    }

    /**
     * 获取会话管理器底座实例
     *
     * @return 会话管理器
     */
    public TcpSessionManager getSessionManager() {
        return sessionManager;
    }

    /**
     * 获取相关性管理器实例
     *
     * @return 相关性管理器
     */
    public TcpRequestCorrelationManager getCorrelationManager() {
        return correlationManager;
    }

    /**
     * 获取跨节点集群桥接器实例（未开启集群桥接时可能为 null）
     *
     * @return 集群桥接器实例
     */
    public TcpClusterBridge getClusterBridge() {
        return clusterBridge;
    }

    private TcpPacket toTcpPacket(Object payload) {
        if (payload instanceof TcpPacket packet) {
            if (packet.getRequestId() == null || packet.getRequestId().isBlank()) {
                packet.setRequestId(IdUtil.fastSimpleUUID());
            }
            if (packet.getPayload() == null || packet.getPayload().isEmpty()) {
                packet.setPayload(TcpPayload.fromJson(packet));
            }
            return packet;
        }
        String requestId = IdUtil.fastSimpleUUID();
        if (payload instanceof TcpPayload tp) {
            return TcpPacket.of(null, requestId, tp);
        }
        if (payload instanceof byte[] bytes) {
            return TcpPacket.of(null, requestId, TcpPayload.of(bytes));
        }
        if (payload instanceof String text) {
            return TcpPacket.of(null, requestId, TcpPayload.of(text));
        }
        return TcpPacket.of(null, requestId, TcpPayload.fromJson(payload));
    }

    private Object wrapPayload(Object payload) {
        if (payload == null) {
            return TcpPayload.empty();
        }
        if (payload instanceof TcpPacket || payload instanceof TcpPayload ||
                payload instanceof byte[] || payload instanceof String) {
            return payload;
        }
        return TcpPayload.fromJson(payload);
    }

    /**
     * 向微服务集群中的指定设备下发数据报文（两级自适应拓扑：本地优先直发，未命中自动转为分布式广播）
     *
     * @param deviceId 目标设备唯一标识
     * @param payload  下发报文载荷
     * @return 是否成功触达设备或投递广播
     */
    public boolean sendToCluster(String deviceId, Object payload) {
        if (clusterBridge != null) {
            return clusterBridge.sendToCluster(deviceId, wrapPayload(payload));
        }
        TcpSession session = sessionManager.getSessionByDeviceId(deviceId);
        if (session != null && session.isActive()) {
            session.send(wrapPayload(payload));
            return true;
        }
        return false;
    }

    /**
     * 向微服务集群中全部在线设备全量广播报文
     *
     * @param payload 广播报文载荷
     * @return 是否成功触发广播
     */
    public boolean broadcastToCluster(Object payload) {
        if (clusterBridge != null) {
            return clusterBridge.broadcastToCluster(wrapPayload(payload));
        }
        broadcast(payload);
        return true;
    }

    /**
     * 向微服务集群中属于指定逻辑分组的全部在线设备跨节点组播报文（本地直发优先 + 分布式集群广播）
     *
     * @param group   业务逻辑分组标识
     * @param payload 组播报文载荷
     * @return 是否成功触发本地直发或分布式组播
     */
    public boolean sendGroupToCluster(String group, Object payload) {
        if (group == null) {
            return false;
        }
        if (clusterBridge != null) {
            return clusterBridge.sendGroupToCluster(group, wrapPayload(payload));
        }
        sendToGroup(group, payload);
        return true;
    }
}
