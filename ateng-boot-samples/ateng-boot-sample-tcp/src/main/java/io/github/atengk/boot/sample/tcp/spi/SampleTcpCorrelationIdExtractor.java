package io.github.atengk.boot.sample.tcp.spi;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.tcp.correlation.TcpCorrelationIdExtractor;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 车联网扩展请求关联流水号提取器（TcpCorrelationIdExtractor SPI 示范）
 * 支持从 TcpPacket 属性/Header、JSON 载荷字段以及 "CORR:&lt;id&gt;:" 自定义前缀文本中提取关联 ID
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Component
public class SampleTcpCorrelationIdExtractor implements TcpCorrelationIdExtractor {

    private static final String CORR_PREFIX = "CORR:";

    private final SampleTcpGovernanceState governanceState;

    public SampleTcpCorrelationIdExtractor(SampleTcpGovernanceState governanceState) {
        this.governanceState = governanceState;
    }

    @Override
    public String extract(Object message) {
        if (message == null) {
            return null;
        }
        String extracted = doExtract(message);
        if (StringUtils.hasText(extracted)) {
            governanceState.recordCorrelationExtracted();
            return extracted;
        }
        return null;
    }

    private String doExtract(Object message) {
        if (message instanceof TcpPacket packet) {
            if (StringUtils.hasText(packet.getRequestId())) {
                return packet.getRequestId();
            }
            String headerId = packet.getHeader("requestId", String.class);
            if (StringUtils.hasText(headerId)) {
                return headerId;
            }
            if (packet.getPayload() != null) {
                return extractFromText(packet.getPayload().asString());
            }
            return null;
        }
        if (message instanceof TcpPayload payload) {
            return extractFromText(payload.asString());
        }
        if (message instanceof Map<?, ?> map) {
            Object requestId = map.get("requestId");
            return requestId != null ? requestId.toString() : null;
        }
        if (message instanceof String text) {
            return extractFromText(text);
        }
        return null;
    }

    private String extractFromText(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith(CORR_PREFIX)) {
            int nextColon = trimmed.indexOf(':', CORR_PREFIX.length());
            if (nextColon > CORR_PREFIX.length()) {
                return trimmed.substring(CORR_PREFIX.length(), nextColon);
            }
        }
        if (trimmed.startsWith("{")) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = JsonUtil.parseObject(trimmed, Map.class);
                if (parsed != null && parsed.get("requestId") != null) {
                    return parsed.get("requestId").toString();
                }
            } catch (Exception ignored) {
                // 非 JSON 载荷直接忽略并交由后续提取器兜底
            }
        }
        return null;
    }
}
