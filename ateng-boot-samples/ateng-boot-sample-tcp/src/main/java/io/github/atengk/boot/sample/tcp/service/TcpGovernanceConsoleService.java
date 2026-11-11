package io.github.atengk.boot.sample.tcp.service;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.sample.tcp.dto.ClusterDispatchRequest;
import io.github.atengk.boot.sample.tcp.dto.SessionBackpressureRequest;
import io.github.atengk.boot.sample.tcp.dto.SpiProbeRequest;
import io.github.atengk.boot.sample.tcp.dto.TrafficShapingAdjustRequest;
import io.github.atengk.boot.sample.tcp.spi.SampleTcpGovernanceState;
import io.github.atengk.boot.sample.tcp.spi.SampleTcpPacketFilter;
import io.github.atengk.boot.sample.tcp.vo.TcpDispatchResultVO;
import io.github.atengk.boot.sample.tcp.vo.TcpGovernanceOverviewVO;
import io.github.atengk.boot.sample.tcp.vo.TcpSessionInfoVO;
import io.github.atengk.boot.sample.tcp.vo.TcpSpiProbeVO;
import io.github.atengk.boot.sample.tcp.vo.TcpTrafficShapingVO;
import io.github.atengk.boot.tcp.actuator.TcpActuatorEndpoint;
import io.github.atengk.boot.tcp.client.TcpClient;
import io.github.atengk.boot.tcp.client.TcpClientRegistry;
import io.github.atengk.boot.tcp.cluster.TcpClusterBridge;
import io.github.atengk.boot.tcp.correlation.TcpCorrelationIdExtractor;
import io.github.atengk.boot.tcp.exception.TcpException;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.properties.AtengTcpProperties;
import io.github.atengk.boot.tcp.route.TcpRouteKeyExtractor;
import io.github.atengk.boot.tcp.server.TcpServer;
import io.github.atengk.boot.tcp.server.TcpServerRegistry;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import io.github.atengk.boot.tcp.spi.TcpChannelCustomizer;
import io.github.atengk.boot.tcp.spi.TcpMessageHandler;
import io.github.atengk.boot.tcp.spi.TcpPacketFilter;
import io.github.atengk.boot.tcp.template.TcpClientTemplate;
import io.github.atengk.boot.tcp.template.TcpServerTemplate;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import io.netty.handler.traffic.TrafficCounter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TCP 全链路 SPI 可观测、背压调优、流量整形与集群桥接治理服务
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Service
@RequiredArgsConstructor
public class TcpGovernanceConsoleService {

    private static final long FILTER_PROBE_WAIT_MS = 2000L;

    private final AtengTcpProperties tcpProperties;
    private final TcpServerRegistry tcpServerRegistry;
    private final TcpServerTemplate tcpServerTemplate;
    private final ObjectProvider<TcpClientRegistry> tcpClientRegistryProvider;
    private final TcpSessionManager sessionManager;
    private final TcpEndpointConsoleService endpointConsoleService;
    private final VehicleTelemetryScenarioService scenarioService;
    private final SampleTcpGovernanceState governanceState;
    private final ObjectProvider<TcpClusterBridge> clusterBridgeProvider;
    private final ObjectProvider<TcpActuatorEndpoint> actuatorEndpointProvider;
    private final ObjectProvider<List<TcpPacketFilter>> packetFiltersProvider;
    private final ObjectProvider<List<TcpChannelCustomizer>> channelCustomizersProvider;
    private final ObjectProvider<List<TcpMessageHandler<?>>> messageHandlersProvider;
    private final ObjectProvider<List<TcpCorrelationIdExtractor>> correlationExtractorsProvider;
    private final ObjectProvider<List<TcpRouteKeyExtractor>> routeKeyExtractorsProvider;

    /**
     * 汇总全量 SPI 组件装配清单、流量整形快照、集群桥接模式与 Actuator 运维探针数据
     *
     * @return 治理态势总览视图
     */
    public TcpGovernanceOverviewVO getOverview() {
        TcpClusterBridge bridge = clusterBridgeProvider.getIfAvailable();
        String bridgeType = bridge != null ? bridge.getClass().getSimpleName() : "NONE";
        boolean redisClusterEnabled = tcpProperties.getCluster() != null
                && tcpProperties.getCluster().getRedis() != null
                && tcpProperties.getCluster().getRedis().isEnabled();

        List<TcpTrafficShapingVO> shapingSnapshots = collectTrafficShapingSnapshots();
        TcpActuatorEndpoint actuator = actuatorEndpointProvider.getIfAvailable();
        Map<String, Object> actuatorInfo = actuator != null ? actuator.info() : Collections.emptyMap();

        return new TcpGovernanceOverviewVO(
                bridgeType,
                redisClusterEnabled,
                extractClassNames(packetFiltersProvider.getIfAvailable(Collections::emptyList)),
                extractClassNames(channelCustomizersProvider.getIfAvailable(Collections::emptyList)),
                extractClassNames(messageHandlersProvider.getIfAvailable(Collections::emptyList)),
                extractClassNames(correlationExtractorsProvider.getIfAvailable(Collections::emptyList)),
                extractClassNames(routeKeyExtractorsProvider.getIfAvailable(Collections::emptyList)),
                governanceState.getServerChannelsCustomized(),
                governanceState.getClientChannelsCustomized(),
                governanceState.getCustomizedTargets(),
                governanceState.getInboundPassedCount(),
                governanceState.getInboundBlockedCount(),
                governanceState.getOutboundPassedCount(),
                governanceState.getCorrelationExtractedCount(),
                governanceState.getAuditPingHandledCount(),
                governanceState.getAuditLogs(),
                scenarioService.getEventLogs(),
                shapingSnapshots,
                actuatorInfo
        );
    }

    /**
     * 动态调整指定设备在线会话的出站背压流控策略与最大阻塞超时（调用 TcpSession 两参重载契约）
     *
     * @param request 背压配置请求
     * @return 更新后的会话只读视图
     */
    public TcpSessionInfoVO adjustBackpressure(SessionBackpressureRequest request) {
        TcpSession session = sessionManager.getSessionByDeviceId(request.getDeviceId());
        if (session == null || !session.isActive()) {
            throw new BusinessException(CommonResultCode.NOT_FOUND,
                    "未找到目标设备的活跃会话，无法调整背压策略: deviceId=" + request.getDeviceId());
        }
        try {
            session.setBackpressurePolicy(request.getPolicy(), Duration.ofMillis(request.getTimeoutMillis()));
            return endpointConsoleService.toSessionVO(session);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    /**
     * 通过 {@link TcpClusterBridge} 执行集群级单播、组播或广播（离线走 LocalTcpClusterBridge，local 走 Redis Pub/Sub）
     *
     * @param request 集群分发请求
     * @return 分发回执视图
     */
    public TcpDispatchResultVO clusterDispatch(ClusterDispatchRequest request) {
        TcpClusterBridge bridge = clusterBridgeProvider.getIfAvailable();
        String bridgeType = bridge != null ? bridge.getClass().getSimpleName() : "NONE";
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("command", "CLUSTER_NOTICE");
        envelope.put("scope", request.getMode());
        envelope.put("message", request.getMessage());
        envelope.put("timestamp", System.currentTimeMillis());

        return switch (request.getMode()) {
            case "DEVICE" -> {
                boolean sent = tcpServerTemplate.sendToCluster(request.getTarget(), envelope);
                int hit = sessionManager.getSessionByDeviceId(request.getTarget()) != null ? 1 : 0;
                yield new TcpDispatchResultVO("DEVICE", request.getTarget(), true, bridgeType, hit, sent, request.getMessage());
            }
            case "GROUP" -> {
                boolean sent = tcpServerTemplate.sendGroupToCluster(request.getTarget(), envelope);
                int hit = sessionManager.getSessionsByGroup(request.getTarget()).size();
                yield new TcpDispatchResultVO("GROUP", request.getTarget(), true, bridgeType, hit, sent, request.getMessage());
            }
            default -> {
                boolean sent = tcpServerTemplate.broadcastToCluster(envelope);
                yield new TcpDispatchResultVO("BROADCAST", "*", true, bridgeType, sessionManager.getActiveCount(), sent, request.getMessage());
            }
        };
    }

    /**
     * 实时调优指定服务端实例的全局读写流量整形限速上限
     *
     * @param request 限速调节请求
     * @return 更新后的流量整形指标视图
     */
    public TcpTrafficShapingVO adjustTrafficShaping(TrafficShapingAdjustRequest request) {
        TcpServer server = tcpServerRegistry.getServer(request.getServerName());
        if (server == null) {
            throw new BusinessException(CommonResultCode.NOT_FOUND, "未找到 TCP 服务端实例: " + request.getServerName());
        }
        GlobalTrafficShapingHandler handler = server.getGlobalTrafficShapingHandler();
        if (handler == null) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST,
                    "服务端 [" + request.getServerName() + "] 未开启全局流量整形处理器");
        }
        handler.configure(request.getWriteLimitBytesPerSec(), request.getReadLimitBytesPerSec());
        return toTrafficShapingVO(request.getServerName(), handler);
    }

    /**
     * 触发实时 SPI 联动链路探针，验证关联提取器、出入站过滤器与函数式消息处理器
     *
     * @param request 探针模式与参数
     * @return 探针执行结果与 SPI 实时计数视图
     */
    public TcpSpiProbeVO probeSpi(SpiProbeRequest request) {
        TcpClientRegistry clientRegistry = tcpClientRegistryProvider.getIfAvailable();
        TcpClient defaultClient = clientRegistry != null ? clientRegistry.getDefaultClient() : null;
        if (defaultClient == null || !defaultClient.isConnected()) {
            throw new BusinessException(CommonResultCode.SERVICE_UNAVAILABLE,
                    "默认回环 TCP 客户端未处于连接状态，无法触发端到端 SPI 探针");
        }
        TcpClientTemplate clientTemplate = clientRegistry.getRequiredTemplate("default");
        String requestId = StringUtils.hasText(request.getRequestId()) ? request.getRequestId() : IdUtil.fastSimpleUUID();

        try {
            if ("FILTER_BLOCK".equalsIgnoreCase(request.getMode())) {
                long beforeBlocked = governanceState.getInboundBlockedCount();
                Map<String, Object> blockedPayload = new LinkedHashMap<>();
                blockedPayload.put("command", "AUDIT_PING");
                blockedPayload.put("requestId", requestId);
                blockedPayload.put("note", SampleTcpPacketFilter.BLOCKED_SIGNATURE + ":" + request.getNote());
                clientTemplate.send(TcpPacket.of("AUDIT_PING", requestId, TcpPayload.of(JsonUtil.toJsonString(blockedPayload))));
                awaitBlockedCountIncrement(beforeBlocked);
                return buildProbeSnapshot(request.getMode(), requestId, "BLOCKED_BY_SAMPLE_PACKET_FILTER");
            }

            Map<String, Object> rpcPayload = new LinkedHashMap<>();
            rpcPayload.put("command", "AUDIT_PING");
            rpcPayload.put("requestId", requestId);
            rpcPayload.put("note", request.getNote());
            TcpPacket ack = clientTemplate.request(
                    TcpPacket.of("AUDIT_PING", requestId, TcpPayload.of(JsonUtil.toJsonString(rpcPayload))),
                    Duration.ofSeconds(3)
            );
            String replyText = ack != null && ack.getPayload() != null ? ack.getPayload().asString() : "";
            return buildProbeSnapshot(request.getMode(), requestId, replyText);
        } catch (TcpException ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR,
                    "SPI 链路探针执行失败: " + ex.getMessage(), ex);
        }
    }

    private void awaitBlockedCountIncrement(long beforeBlocked) {
        long deadline = System.currentTimeMillis() + FILTER_PROBE_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (governanceState.getInboundBlockedCount() > beforeBlocked) {
                return;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private TcpSpiProbeVO buildProbeSnapshot(String mode, String requestId, String replyPayload) {
        return new TcpSpiProbeVO(
                mode,
                requestId,
                replyPayload,
                governanceState.getInboundPassedCount(),
                governanceState.getInboundBlockedCount(),
                governanceState.getOutboundPassedCount(),
                governanceState.getCorrelationExtractedCount(),
                governanceState.getAuditPingHandledCount(),
                governanceState.getLastAuditCorrelationId()
        );
    }

    private List<TcpTrafficShapingVO> collectTrafficShapingSnapshots() {
        Map<String, TcpServer> servers = tcpServerRegistry.getAllServers();
        if (servers.isEmpty()) {
            return Collections.emptyList();
        }
        List<TcpTrafficShapingVO> snapshots = new ArrayList<>(servers.size());
        for (Map.Entry<String, TcpServer> entry : servers.entrySet()) {
            GlobalTrafficShapingHandler handler = entry.getValue().getGlobalTrafficShapingHandler();
            if (handler != null) {
                snapshots.add(toTrafficShapingVO(entry.getKey(), handler));
            } else {
                snapshots.add(new TcpTrafficShapingVO(entry.getKey(), false, 0L, 0L, 0L, 0L, 0L, 0L));
            }
        }
        return snapshots;
    }

    private TcpTrafficShapingVO toTrafficShapingVO(String serverName, GlobalTrafficShapingHandler handler) {
        TrafficCounter counter = handler.trafficCounter();
        return new TcpTrafficShapingVO(
                serverName,
                true,
                handler.getWriteLimit(),
                handler.getReadLimit(),
                counter != null ? counter.cumulativeWrittenBytes() : 0L,
                counter != null ? counter.cumulativeReadBytes() : 0L,
                counter != null ? counter.lastWriteThroughput() : 0L,
                counter != null ? counter.lastReadThroughput() : 0L
        );
    }

    private List<String> extractClassNames(List<?> beans) {
        if (beans == null || beans.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>(beans.size());
        for (Object bean : beans) {
            if (bean != null) {
                names.add(bean.getClass().getSimpleName());
            }
        }
        return names;
    }
}
