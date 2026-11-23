package io.github.atengk.boot.sample.tcp.vo;

import java.io.Serializable;

/**
 * SPI 治理链路探针实测结果视图对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
public record TcpSpiProbeVO(
        String mode,
        String requestId,
        String replyPayload,
        long inboundPassedCount,
        long inboundBlockedCount,
        long outboundPassedCount,
        long correlationExtractedCount,
        long auditPingHandledCount,
        String lastAuditCorrelationId
) implements Serializable {}
