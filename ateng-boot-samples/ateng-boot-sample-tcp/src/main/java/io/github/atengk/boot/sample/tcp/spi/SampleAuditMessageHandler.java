package io.github.atengk.boot.sample.tcp.spi;

import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.github.atengk.boot.tcp.spi.TcpMessageHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 函数式 SPI 审计探针处理器（TcpMessageHandler SPI 示范）
 * 与声明式 {@code @TcpListener} 并存运行，接管 {@code AUDIT_PING} 指令并回写带原关联流水号的 {@code AUDIT_PONG} 应答
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SampleAuditMessageHandler implements TcpMessageHandler<Map<String, Object>> {

    private final SampleTcpGovernanceState governanceState;

    @Override
    public String getCommand() {
        return "AUDIT_PING";
    }

    @Override
    public void handle(TcpSession session, Map<String, Object> payload, TcpPacket packet) {
        String requestId = packet != null ? packet.getRequestId() : null;
        if (!StringUtils.hasText(requestId) && payload != null && payload.get("requestId") != null) {
            requestId = payload.get("requestId").toString();
        }
        String note = payload != null && payload.get("note") != null ? payload.get("note").toString() : "DEFAULT_PROBE";
        governanceState.recordAuditPing(requestId, note);
        log.info("[SPI-HANDLER] 处理 AUDIT_PING 审计探针: sessionId={}, requestId={}, note={}",
                session != null ? session.getSessionId() : null, requestId, note);

        if (session != null && session.isActive()) {
            Map<String, Object> ack = new LinkedHashMap<>();
            ack.put("command", "AUDIT_PONG");
            if (StringUtils.hasText(requestId)) {
                ack.put("requestId", requestId);
            }
            ack.put("status", "OK");
            ack.put("note", note);
            ack.put("timestamp", System.currentTimeMillis());
            session.send(TcpPacket.of("AUDIT_PONG", requestId, TcpPayload.fromJson(ack)));
        }
    }
}
