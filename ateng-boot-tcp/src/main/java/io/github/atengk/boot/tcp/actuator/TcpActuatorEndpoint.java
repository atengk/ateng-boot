package io.github.atengk.boot.tcp.actuator;

import io.github.atengk.boot.tcp.client.TcpClient;
import io.github.atengk.boot.tcp.client.TcpClientRegistry;
import io.github.atengk.boot.tcp.metric.TcpMetricsCollector;
import io.github.atengk.boot.tcp.server.TcpServer;
import io.github.atengk.boot.tcp.server.TcpServerRegistry;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.lang.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TCP 网络底座云原生运维管控端点
 * <p>
 * 暴露在 /actuator/tcp 路径，提供长连接会话状态、端口监听诊断与网络吞吐度量。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Endpoint(id = "tcp")
public class TcpActuatorEndpoint {

    private final TcpSessionManager sessionManager;
    private final TcpServerRegistry serverRegistry;
    private final TcpClientRegistry clientRegistry;
    private final TcpMetricsCollector metricsCollector;

    public TcpActuatorEndpoint(@Nullable TcpSessionManager sessionManager,
                               @Nullable TcpServerRegistry serverRegistry,
                               @Nullable TcpClientRegistry clientRegistry,
                               @Nullable TcpMetricsCollector metricsCollector) {
        this.sessionManager = sessionManager;
        this.serverRegistry = serverRegistry;
        this.clientRegistry = clientRegistry;
        this.metricsCollector = metricsCollector;
    }

    @ReadOperation
    public Map<String, Object> info() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");

        // 1. 会话概览
        int activeSessions = sessionManager != null ? sessionManager.getActiveCount() : 0;
        result.put("activeSessions", activeSessions);

        // 2. 服务端监听诊断
        if (serverRegistry != null) {
            Map<String, Object> serversInfo = new LinkedHashMap<>();
            for (Map.Entry<String, TcpServer> entry : serverRegistry.getAllServers().entrySet()) {
                TcpServer s = entry.getValue();
                Map<String, Object> sMap = new LinkedHashMap<>();
                sMap.put("name", s.getName());
                sMap.put("running", s.isRunning());
                sMap.put("host", s.getProperties().getHost());
                sMap.put("port", s.getProperties().getPort());
                sMap.put("actualPort", s.getActualPort());
                sMap.put("framingMode", s.getProperties().getFraming().getMode());
                sMap.put("sslEnabled", s.getProperties().getSsl().isEnabled());
                serversInfo.put(entry.getKey(), sMap);
            }
            result.put("servers", serversInfo);
        }

        // 3. 客户端连接诊断
        if (clientRegistry != null) {
            Map<String, Object> clientsInfo = new LinkedHashMap<>();
            for (Map.Entry<String, TcpClient> entry : clientRegistry.getAllClients().entrySet()) {
                TcpClient c = entry.getValue();
                Map<String, Object> cMap = new LinkedHashMap<>();
                cMap.put("name", c.getName());
                cMap.put("running", c.isRunning());
                cMap.put("connected", c.isConnected());
                cMap.put("host", c.getProperties().getHost());
                cMap.put("port", c.getProperties().getPort());
                cMap.put("reconnectEnabled", c.getProperties().getReconnect().isEnabled());
                clientsInfo.put(entry.getKey(), cMap);
            }
            result.put("clients", clientsInfo);
        }

        // 4. 吞吐与流量度量
        if (metricsCollector != null) {
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("connectionsTotal", metricsCollector.getConnectionsTotal());
            metrics.put("messagesReceived", metricsCollector.getMessagesReceived());
            metrics.put("messagesSent", metricsCollector.getMessagesSent());
            metrics.put("bytesReceived", metricsCollector.getBytesReceived());
            metrics.put("bytesSent", metricsCollector.getBytesSent());
            result.put("metrics", metrics);
        }

        return result;
    }

    @ReadOperation
    public Map<String, Object> sessionDetail(@Selector String deviceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (sessionManager == null) {
            result.put("error", "TcpSessionManager 未就绪");
            return result;
        }

        TcpSession session = sessionManager.getSessionByDeviceId(deviceId);
        if (session == null) {
            result.put("found", false);
            result.put("deviceId", deviceId);
            return result;
        }

        result.put("found", true);
        result.put("deviceId", session.getDeviceId());
        result.put("sessionId", session.getSessionId());
        result.put("remoteAddress", session.getRemoteAddress() != null ? session.getRemoteAddress().toString() : "unknown");
        result.put("connectedAt", session.getConnectedAt());
        result.put("lastActiveTime", session.getLastActiveTime());
        result.put("active", session.isActive());
        return result;
    }
}
