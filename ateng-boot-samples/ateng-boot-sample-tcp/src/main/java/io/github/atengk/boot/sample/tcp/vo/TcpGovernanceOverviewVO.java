package io.github.atengk.boot.sample.tcp.vo;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TCP 全链路 SPI 扩展、集群桥接与网关运行态势总览视图对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
public record TcpGovernanceOverviewVO(
        String clusterBridgeType,
        boolean redisClusterEnabled,
        List<String> registeredPacketFilters,
        List<String> registeredChannelCustomizers,
        List<String> registeredMessageHandlers,
        List<String> registeredCorrelationExtractors,
        List<String> registeredRouteKeyExtractors,
        long serverChannelsCustomized,
        long clientChannelsCustomized,
        Set<String> customizedTargets,
        long inboundPassedCount,
        long inboundBlockedCount,
        long outboundPassedCount,
        long correlationExtractedCount,
        long auditPingHandledCount,
        List<String> auditLogs,
        List<String> eventLogs,
        List<TcpTrafficShapingVO> trafficShapingSnapshots,
        Map<String, Object> actuatorInfo
) implements Serializable {}
