package io.github.atengk.boot.sample.tcp.service;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.sample.tcp.dto.ClientRpcRequest;
import io.github.atengk.boot.sample.tcp.dto.ClientSendRequest;
import io.github.atengk.boot.sample.tcp.dto.GroupSendRequest;
import io.github.atengk.boot.sample.tcp.dto.ServerBroadcastRequest;
import io.github.atengk.boot.sample.tcp.dto.SessionBindRequest;
import io.github.atengk.boot.sample.tcp.dto.SessionGroupRequest;
import io.github.atengk.boot.sample.tcp.dto.SessionKickRequest;
import io.github.atengk.boot.sample.tcp.model.VehicleStatusVO;
import io.github.atengk.boot.sample.tcp.vo.TcpClientInfoVO;
import io.github.atengk.boot.sample.tcp.vo.TcpClientRpcVO;
import io.github.atengk.boot.sample.tcp.vo.TcpClientSendVO;
import io.github.atengk.boot.sample.tcp.vo.TcpDispatchResultVO;
import io.github.atengk.boot.sample.tcp.vo.TcpServerInfoVO;
import io.github.atengk.boot.sample.tcp.vo.TcpSessionDetailVO;
import io.github.atengk.boot.sample.tcp.vo.TcpSessionInfoVO;
import io.github.atengk.boot.tcp.actuator.TcpActuatorEndpoint;
import io.github.atengk.boot.tcp.client.TcpClient;
import io.github.atengk.boot.tcp.client.TcpClientRegistry;
import io.github.atengk.boot.tcp.cluster.TcpClusterBridge;
import io.github.atengk.boot.tcp.enums.CloseReason;
import io.github.atengk.boot.tcp.exception.TcpException;
import io.github.atengk.boot.tcp.exception.TcpTimeoutException;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.properties.TcpClientProperties;
import io.github.atengk.boot.tcp.properties.TcpIpFilterProperties;
import io.github.atengk.boot.tcp.properties.TcpServerProperties;
import io.github.atengk.boot.tcp.server.TcpServer;
import io.github.atengk.boot.tcp.server.TcpServerRegistry;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import io.github.atengk.boot.tcp.template.TcpClientTemplate;
import io.github.atengk.boot.tcp.template.TcpServerTemplate;
import io.github.atengk.boot.tcp.util.ChecksumUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

/**
 * TCP 服务端、客户端与长连接会话交互编排服务
 * 负责将底层 Netty 实例与会话实体隔离转化为只读视图对象（Strict Zero-Entity-Leakage），
 * 并在边界拦截底层异常转译为带完整因果链的 {@link BusinessException}。
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Service
@RequiredArgsConstructor
public class TcpEndpointConsoleService {

    private final TcpServerRegistry tcpServerRegistry;
    private final TcpServerTemplate tcpServerTemplate;
    private final ObjectProvider<TcpClientRegistry> tcpClientRegistryProvider;
    private final TcpSessionManager sessionManager;
    private final VehicleTelemetryScenarioService scenarioService;
    private final ObjectProvider<TcpClusterBridge> clusterBridgeProvider;
    private final ObjectProvider<TcpActuatorEndpoint> actuatorEndpointProvider;

    /**
     * 查询所有已装配的 TCP 服务端实例运行快照
     *
     * @return 服务端只读视图列表
     */
    public List<TcpServerInfoVO> listServers() {
        Map<String, TcpServer> servers = tcpServerRegistry.getAllServers();
        if (servers.isEmpty()) {
            return Collections.emptyList();
        }
        List<TcpServerInfoVO> result = new ArrayList<>(servers.size());
        for (Map.Entry<String, TcpServer> entry : servers.entrySet()) {
            result.add(toServerVO(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    /**
     * 查询单个指定名称的 TCP 服务端详情
     *
     * @param serverName 服务端注册名称
     * @return 服务端详情视图
     */
    public TcpServerInfoVO getServerInfo(String serverName) {
        TcpServer server = tcpServerRegistry.getServer(serverName);
        if (server == null) {
            throw new BusinessException(CommonResultCode.NOT_FOUND, "未找到 TCP 服务端实例: " + serverName);
        }
        return toServerVO(serverName, server);
    }

    /**
     * 执行全量在线设备广播（支持本地直发或集群桥接广播）
     *
     * @param request 广播请求
     * @return 分发回执视图
     */
    public TcpDispatchResultVO broadcast(ServerBroadcastRequest request) {
        Map<String, Object> body = buildBroadcastEnvelope("SERVER_BROADCAST", request.getMessage());
        int activeCount = sessionManager.getActiveCount();
        String bridgeType = resolveClusterBridgeType();
        if (Boolean.TRUE.equals(request.getCluster())) {
            boolean ok = tcpServerTemplate.broadcastToCluster(body);
            return new TcpDispatchResultVO("BROADCAST", "*", true, bridgeType, activeCount, ok, request.getMessage());
        }
        tcpServerTemplate.broadcast(body);
        return new TcpDispatchResultVO("BROADCAST", "*", false, bridgeType, activeCount, true, request.getMessage());
    }

    /**
     * 执行指定分组设备群发消息（支持本地组播或集群分组广播）
     *
     * @param request 群发请求
     * @return 分发回执视图
     */
    public TcpDispatchResultVO sendToGroup(GroupSendRequest request) {
        Map<String, Object> body = buildBroadcastEnvelope("FLEET_ALERT", request.getMessage());
        body.put("group", request.getGroup());
        int groupCount = sessionManager.getSessionsByGroup(request.getGroup()).size();
        String bridgeType = resolveClusterBridgeType();
        if (Boolean.TRUE.equals(request.getCluster())) {
            boolean ok = tcpServerTemplate.sendGroupToCluster(request.getGroup(), body);
            return new TcpDispatchResultVO("GROUP", request.getGroup(), true, bridgeType, groupCount, ok, request.getMessage());
        }
        tcpServerTemplate.sendToGroup(request.getGroup(), body);
        return new TcpDispatchResultVO("GROUP", request.getGroup(), false, bridgeType, groupCount, true, request.getMessage());
    }

    /**
     * 查询所有已装配的 TCP 客户端实例运行快照
     *
     * @return 客户端只读视图列表
     */
    public List<TcpClientInfoVO> listClients() {
        TcpClientRegistry registry = tcpClientRegistryProvider.getIfAvailable();
        if (registry == null) {
            return Collections.emptyList();
        }
        Map<String, TcpClient> clients = registry.getAllClients();
        if (clients.isEmpty()) {
            return Collections.emptyList();
        }
        List<TcpClientInfoVO> result = new ArrayList<>(clients.size());
        for (Map.Entry<String, TcpClient> entry : clients.entrySet()) {
            result.add(toClientVO(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    /**
     * 使用指定客户端模板单向发送消息（支持 JSON / TEXT / HEX）
     *
     * @param request 客户端推流请求
     * @return 发送回执视图
     */
    public TcpClientSendVO sendByClient(ClientSendRequest request) {
        TcpClientTemplate template = requireConnectedClientTemplate(request.getClientName());
        try {
            Object wireMessage = buildClientOutboundPayload(request);
            int bytesLen = estimateByteLength(request, wireMessage);
            template.send(wireMessage);
            return new TcpClientSendVO(request.getClientName(), request.getPayloadType(), request.getCommand(), true, bytesLen);
        } catch (TcpException ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR,
                    "TCP 客户端推流失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 使用指定客户端模板向上行服务端发起同步 RPC 请求
     *
     * @param request RPC 请求
     * @return RPC 响应回执视图
     */
    public TcpClientRpcVO requestSync(ClientRpcRequest request) {
        TcpClientTemplate template = requireConnectedClientTemplate(request.getClientName());
        String reqId = StringUtils.hasText(request.getRequestId()) ? request.getRequestId() : IdUtil.fastSimpleUUID();
        TcpPacket packet = buildClientRpcPacket(request, reqId);
        long start = System.currentTimeMillis();
        try {
            TcpPacket response = template.request(packet, Duration.ofMillis(request.getTimeoutMillis()));
            return toRpcVO(request, reqId, response, System.currentTimeMillis() - start, false);
        } catch (TcpTimeoutException ex) {
            throw new BusinessException(CommonResultCode.SERVICE_UNAVAILABLE,
                    "TCP 客户端上行 RPC 等待应答超时: command=" + request.getCommand(), ex);
        } catch (TcpException ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR,
                    "TCP 客户端上行 RPC 调用失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 使用指定客户端模板向上行服务端发起异步非阻塞 RPC 请求
     *
     * @param request RPC 请求
     * @return 异步回执 Future
     */
    public CompletableFuture<TcpClientRpcVO> requestAsync(ClientRpcRequest request) {
        TcpClientTemplate template = requireConnectedClientTemplate(request.getClientName());
        String reqId = StringUtils.hasText(request.getRequestId()) ? request.getRequestId() : IdUtil.fastSimpleUUID();
        TcpPacket packet = buildClientRpcPacket(request, reqId);
        long start = System.currentTimeMillis();
        return template.requestAsync(packet, Duration.ofMillis(request.getTimeoutMillis()))
                .handle((response, ex) -> {
                    if (ex == null) {
                        return toRpcVO(request, reqId, response, System.currentTimeMillis() - start, true);
                    }
                    Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TcpTimeoutException || cause instanceof TimeoutException) {
                        throw new BusinessException(CommonResultCode.SERVICE_UNAVAILABLE,
                                "TCP 客户端异步上行 RPC 等待应答超时: command=" + request.getCommand(), cause);
                    }
                    throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR,
                            "TCP 客户端异步上行 RPC 调用失败: " + cause.getMessage(), cause);
                });
    }

    /**
     * 触发指定客户端显式重连，并返回最新连接快照
     *
     * @param clientName 客户端实例名
     * @return 客户端视图对象
     */
    public TcpClientInfoVO reconnectClient(String clientName) {
        TcpClient client = requireClient(requireClientRegistry(), clientName);
        try {
            client.reconnect();
            return toClientVO(clientName, client);
        } catch (Exception ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR,
                    "TCP 客户端重连执行失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 断开指定客户端连接（保留配置便于后续按需重连）
     *
     * @param clientName 客户端实例名
     * @return 客户端视图对象
     */
    public TcpClientInfoVO disconnectClient(String clientName) {
        TcpClient client = requireClient(requireClientRegistry(), clientName);
        client.disconnect();
        return toClientVO(clientName, client);
    }

    /**
     * 查询当前所有活跃 TCP 会话（支持按业务分组过滤）
     *
     * @param group 可选分组过滤名称
     * @return 只读会话视图列表
     */
    public List<TcpSessionInfoVO> listSessions(String group) {
        Collection<TcpSession> sessions = StringUtils.hasText(group)
                ? sessionManager.getSessionsByGroup(group)
                : sessionManager.getAllSessions();
        if (sessions == null || sessions.isEmpty()) {
            return Collections.emptyList();
        }
        List<TcpSessionInfoVO> result = new ArrayList<>(sessions.size());
        for (TcpSession session : sessions) {
            if (session != null) {
                result.add(toSessionVO(session));
            }
        }
        return result;
    }

    /**
     * 聚合查询指定设备的会话属性、最新遥测与 Actuator 快照
     *
     * @param deviceId 设备唯一编号
     * @return 设备会话详情视图
     */
    public TcpSessionDetailVO getSessionDetail(String deviceId) {
        TcpSession session = sessionManager.getSessionByDeviceId(deviceId);
        VehicleStatusVO statusVO = scenarioService.getVehicleStatus(deviceId);
        if (session == null && statusVO == null) {
            throw new BusinessException(CommonResultCode.NOT_FOUND, "未找到设备会话及遥测记录: deviceId=" + deviceId);
        }
        TcpSessionInfoVO sessionVO = session != null ? toSessionVO(session) : null;
        TcpActuatorEndpoint actuator = actuatorEndpointProvider.getIfAvailable();
        Map<String, Object> actuatorSnapshot = actuator != null ? actuator.sessionDetail(deviceId) : Collections.emptyMap();
        return new TcpSessionDetailVO(sessionVO, statusVO, actuatorSnapshot);
    }

    /**
     * 为在线会话手工绑定业务设备编号
     *
     * @param request 绑定请求
     * @return 更新后的会话只读视图
     */
    public TcpSessionInfoVO bindDevice(SessionBindRequest request) {
        TcpSession existing = sessionManager.getSession(request.getSessionId());
        if (existing == null || !existing.isActive()) {
            throw new BusinessException(CommonResultCode.NOT_FOUND,
                    "未找到活跃会话，无法绑定设备: sessionId=" + request.getSessionId());
        }
        sessionManager.bind(request.getSessionId(), request.getDeviceId());
        return toSessionVO(existing);
    }

    /**
     * 动态变更设备所属业务分组（加群 / 退群）
     *
     * @param request 分组操作请求
     * @return 操作是否成功
     */
    public boolean manageGroup(SessionGroupRequest request) {
        TcpSession session = sessionManager.getSessionByDeviceId(request.getDeviceId());
        if (session == null || !session.isActive()) {
            throw new BusinessException(CommonResultCode.NOT_FOUND,
                    "设备未在线，无法变更分组: deviceId=" + request.getDeviceId());
        }
        if ("JOIN".equalsIgnoreCase(request.getAction())) {
            sessionManager.addToGroup(request.getGroup(), session.getSessionId());
        } else {
            sessionManager.removeFromGroup(request.getGroup(), session.getSessionId());
        }
        return true;
    }

    /**
     * 强制踢下线或关闭指定设备/会话长连接
     *
     * @param request 强踢请求
     * @return 是否命中并关闭了活跃会话
     */
    public boolean kickSession(SessionKickRequest request) {
        if (StringUtils.hasText(request.getDeviceId())) {
            TcpSession session = sessionManager.getSessionByDeviceId(request.getDeviceId());
            if (session == null) {
                throw new BusinessException(CommonResultCode.NOT_FOUND,
                        "未找到对应设备的活跃会话: deviceId=" + request.getDeviceId());
            }
            if (StringUtils.hasText(request.getReason())) {
                session.setAttribute("kickReason", request.getReason());
            }
            sessionManager.closeDevice(request.getDeviceId(), CloseReason.KICK_OUT);
            return true;
        }
        TcpSession session = sessionManager.getSession(request.getSessionId());
        if (session == null) {
            throw new BusinessException(CommonResultCode.NOT_FOUND,
                    "未找到指定会话: sessionId=" + request.getSessionId());
        }
        if (StringUtils.hasText(request.getReason())) {
            session.setAttribute("kickReason", request.getReason());
        }
        sessionManager.closeSession(request.getSessionId(), CloseReason.KICK_OUT);
        return true;
    }

    /**
     * 将内部 {@link TcpSession} 安全映射为不可变视图对象
     *
     * @param session TCP 会话实体
     * @return 只读会话视图
     */
    public TcpSessionInfoVO toSessionVO(TcpSession session) {
        Map<String, Object> safeAttrs = new LinkedHashMap<>();
        session.getAttributes().forEach((k, v) -> safeAttrs.put(k, v != null ? String.valueOf(v) : null));
        Long timeoutMs = session.getBackpressureTimeout() != null ? session.getBackpressureTimeout().toMillis() : null;
        return new TcpSessionInfoVO(
                session.getSessionId(),
                session.getDeviceId(),
                session.getRemoteAddress() != null ? session.getRemoteAddress().toString() : null,
                session.getLocalAddress() != null ? session.getLocalAddress().toString() : null,
                session.isActive(),
                session.isWritable(),
                session.getPendingWriteBytes(),
                session.getBackpressurePolicy() != null ? session.getBackpressurePolicy().name() : null,
                timeoutMs,
                session.getConnectedAt(),
                session.getLastActiveTime(),
                session.getCloseReason() != null ? session.getCloseReason().name() : null,
                Collections.unmodifiableMap(safeAttrs)
        );
    }

    private TcpClientTemplate requireConnectedClientTemplate(String clientName) {
        TcpClientRegistry registry = requireClientRegistry();
        TcpClient client = requireClient(registry, clientName);
        if (!client.isConnected()) {
            throw new BusinessException(CommonResultCode.SERVICE_UNAVAILABLE,
                    "TCP 客户端当前处于未连接状态: " + clientName);
        }
        return registry.getRequiredTemplate(clientName);
    }

    private TcpClientRegistry requireClientRegistry() {
        TcpClientRegistry registry = tcpClientRegistryProvider.getIfAvailable();
        if (registry == null) {
            throw new BusinessException(CommonResultCode.SERVICE_UNAVAILABLE, "未启用任何 TCP 客户端实例");
        }
        return registry;
    }

    private TcpClient requireClient(TcpClientRegistry registry, String clientName) {
        TcpClient client = registry.getClient(clientName);
        if (client == null) {
            throw new BusinessException(CommonResultCode.NOT_FOUND, "未找到 TCP 客户端实例: " + clientName);
        }
        return client;
    }

    private TcpServerInfoVO toServerVO(String name, TcpServer server) {
        TcpServerProperties props = server.getProperties();
        TcpIpFilterProperties ipFilter = props.getIpFilter();
        return new TcpServerInfoVO(
                name,
                server.isRunning(),
                props.getHost(),
                props.getPort(),
                server.getActualPort(),
                props.getFraming().getMode().name(),
                props.getSsl().isEnabled(),
                props.getSsl().getClientAuth() != null ? props.getSsl().getClientAuth().name() : "NONE",
                server.getSessionManager().getActiveCount(),
                ipFilter != null && ipFilter.isEnabled(),
                ipFilter != null && ipFilter.getWhitelist() != null ? new ArrayList<>(ipFilter.getWhitelist()) : Collections.emptyList(),
                ipFilter != null && ipFilter.getBlacklist() != null ? new ArrayList<>(ipFilter.getBlacklist()) : Collections.emptyList(),
                props.getTrafficShaping() != null && props.getTrafficShaping().isEnabled()
        );
    }

    private TcpClientInfoVO toClientVO(String name, TcpClient client) {
        TcpClientProperties props = client.getProperties();
        TcpSession session = client.getSession();
        return new TcpClientInfoVO(
                name,
                props.getHost(),
                props.getPort(),
                client.isRunning(),
                client.isConnected(),
                session != null ? session.getSessionId() : null,
                props.getFraming().getMode().name(),
                props.getSsl().isEnabled(),
                props.getHeartbeat().isEnabled(),
                props.getReconnect().isEnabled()
        );
    }

    private Object buildClientOutboundPayload(ClientSendRequest request) {
        if ("HEX".equalsIgnoreCase(request.getPayloadType())) {
            try {
                byte[] bytes = ChecksumUtil.fromHexString(request.getContent());
                return TcpPacket.ofBytes(request.getCommand(), bytes);
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(CommonResultCode.BAD_REQUEST,
                        "非法十六进制字符串: " + ex.getMessage(), ex);
            }
        }
        if ("TEXT".equalsIgnoreCase(request.getPayloadType())) {
            return StringUtils.hasText(request.getCommand())
                    ? TcpPacket.ofText(request.getCommand(), request.getContent())
                    : TcpPacket.ofText(request.getContent());
        }
        if (StringUtils.hasText(request.getCommand())) {
            String trimmed = request.getContent().trim();
            if (trimmed.startsWith("{")) {
                return TcpPacket.of(request.getCommand(), null, TcpPayload.of(trimmed));
            }
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("command", request.getCommand());
            wrapper.put("note", request.getContent());
            return TcpPacket.ofJson(request.getCommand(), wrapper);
        }
        return TcpPacket.of(TcpPayload.of(request.getContent()));
    }

    private int estimateByteLength(ClientSendRequest request, Object wireMessage) {
        if (wireMessage instanceof TcpPacket p && p.getPayload() != null) {
            return p.getPayload().length();
        }
        if (request != null && request.getContent() != null) {
            return request.getContent().getBytes(StandardCharsets.UTF_8).length;
        }
        return 0;
    }

    private TcpPacket buildClientRpcPacket(ClientRpcRequest request, String requestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("command", request.getCommand());
        body.put("requestId", requestId);
        if (request.getPayload() != null) {
            body.putAll(request.getPayload());
        }
        return TcpPacket.of(request.getCommand(), requestId, TcpPayload.of(JsonUtil.toJsonString(body)));
    }

    private TcpClientRpcVO toRpcVO(ClientRpcRequest request, String requestId, TcpPacket response, long elapsedMs, boolean asyncMode) {
        String respCmd = response != null ? response.getCommand() : null;
        String respText = (response != null && response.getPayload() != null) ? response.getPayload().asString() : null;
        return new TcpClientRpcVO(
                request.getClientName(),
                request.getCommand(),
                requestId,
                asyncMode,
                respCmd,
                respText,
                elapsedMs
        );
    }

    private Map<String, Object> buildBroadcastEnvelope(String command, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("command", command);
        body.put("message", message);
        body.put("timestamp", System.currentTimeMillis());
        return body;
    }

    private String resolveClusterBridgeType() {
        TcpClusterBridge bridge = clusterBridgeProvider.getIfAvailable();
        return bridge != null ? bridge.getClass().getSimpleName() : "NONE";
    }
}
