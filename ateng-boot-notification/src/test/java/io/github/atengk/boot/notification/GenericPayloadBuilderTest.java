package io.github.atengk.boot.notification;

import io.github.atengk.boot.notification.adapter.GenericPayloadBuilder;
import io.github.atengk.boot.notification.model.CardButton;
import io.github.atengk.boot.notification.model.CardMessage;
import io.github.atengk.boot.notification.model.ImageMessage;
import io.github.atengk.boot.notification.model.MarkdownMessage;
import io.github.atengk.boot.notification.model.RawMessage;
import io.github.atengk.boot.notification.model.TextMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 通用自定义 Webhook 报文构建适配器单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
class GenericPayloadBuilderTest {

    private final GenericPayloadBuilder builder = new GenericPayloadBuilder();

    @Test
    @DisplayName("验证 TextMessage 转译为通用 Webhook 标准报文")
    void testBuildTextPayload() {
        TextMessage text = TextMessage.of("通用告警")
                .channel("ops")
                .atAll();

        Map<String, Object> payload = builder.buildPayload(text);

        assertEquals("text", payload.get("msgType"));
        assertEquals("ops", payload.get("channel"));
        assertEquals("通用告警", payload.get("content"));
        assertNotNull(payload.get("timestamp"));
        assertNotNull(payload.get("mention"));
    }

    @Test
    @DisplayName("验证 CardMessage 转译为通用 Webhook 标准结构")
    void testBuildCardPayload() {
        CardMessage card = CardMessage.of("卡片标题", "卡片内容")
                .button(CardButton.of("查看", "https://example.com"))
                .singleAction("跳转", "https://single.com");

        Map<String, Object> payload = builder.buildPayload(card);

        assertEquals("card", payload.get("msgType"));
        assertEquals("卡片标题", payload.get("title"));
        assertEquals("卡片内容", payload.get("content"));
        assertEquals("跳转", payload.get("singleActionText"));
        assertEquals("https://single.com", payload.get("singleActionUrl"));
    }

    @Test
    @DisplayName("验证 ImageMessage 转译为通用 Webhook 结构")
    @SuppressWarnings("unchecked")
    void testBuildImagePayload() {
        ImageMessage img = new ImageMessage("b64", "md5");
        Map<String, Object> payload = builder.buildPayload(img);

        assertEquals("image", payload.get("msgType"));
        Map<String, String> image = (Map<String, String>) payload.get("image");
        assertEquals("b64", image.get("base64"));
        assertEquals("md5", image.get("md5"));
    }

    @Test
    @DisplayName("验证 RawMessage 原生透传通用 Webhook")
    void testBuildRawPayload() {
        RawMessage raw = RawMessage.of(Map.of("custom_event", "deploy_success"));
        Map<String, Object> payload = builder.buildPayload(raw);

        assertEquals("deploy_success", payload.get("custom_event"));
    }
}
