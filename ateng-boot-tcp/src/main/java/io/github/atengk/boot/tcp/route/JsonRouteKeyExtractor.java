package io.github.atengk.boot.tcp.route;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 基于 JSON 属性键的路由命令字提取器
 * <p>
 * 从 JSON 格式的报文载荷中提取指定的属性字段作为路由命令字。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class JsonRouteKeyExtractor implements TcpRouteKeyExtractor {

    private static final List<String> DEFAULT_PROPERTY_NAMES = List.of("command", "action", "cmd", "msgType", "type", "code");

    private final List<String> propertyNames;

    public JsonRouteKeyExtractor() {
        this(DEFAULT_PROPERTY_NAMES);
    }

    public JsonRouteKeyExtractor(String... propertyNames) {
        this(propertyNames != null ? Arrays.asList(propertyNames) : Collections.emptyList());
    }

    public JsonRouteKeyExtractor(List<String> propertyNames) {
        if (propertyNames == null || propertyNames.isEmpty()) {
            this.propertyNames = DEFAULT_PROPERTY_NAMES;
        } else {
            this.propertyNames = List.copyOf(propertyNames);
        }
    }

    @Override
    public String extract(TcpPacket packet) {
        if (packet == null) {
            return null;
        }
        if (StringUtils.hasText(packet.getCommand())) {
            return packet.getCommand();
        }
        TcpPayload payload = packet.getPayload();
        if (payload == null || payload.isEmpty()) {
            return null;
        }

        String jsonText = payload.asText();
        if (!StringUtils.hasText(jsonText)) {
            return null;
        }

        try {
            JsonNode rootNode = JsonUtil.parseObject(jsonText, JsonNode.class);
            if (rootNode == null || !rootNode.isObject()) {
                return null;
            }
            for (String propName : propertyNames) {
                JsonNode valNode = rootNode.get(propName);
                if (valNode != null && !valNode.isNull()) {
                    String text = valNode.asText();
                    if (StringUtils.hasText(text)) {
                        return text.trim();
                    }
                }
            }
        } catch (Exception ignored) {
            // 非 JSON 格式或解析失败时静默跳过
        }
        return null;
    }
}
