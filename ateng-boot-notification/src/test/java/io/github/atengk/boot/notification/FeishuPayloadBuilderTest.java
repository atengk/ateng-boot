package io.github.atengk.boot.notification;

import io.github.atengk.boot.notification.adapter.FeishuPayloadBuilder;
import io.github.atengk.boot.notification.model.CardButton;
import io.github.atengk.boot.notification.model.CardButtonType;
import io.github.atengk.boot.notification.model.CardColor;
import io.github.atengk.boot.notification.model.CardMessage;
import io.github.atengk.boot.notification.model.MarkdownMessage;
import io.github.atengk.boot.notification.model.RawMessage;
import io.github.atengk.boot.notification.model.TextMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 飞书群机器人报文构建适配器单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
class FeishuPayloadBuilderTest {

    private final FeishuPayloadBuilder builder = new FeishuPayloadBuilder();

    @Test
    @DisplayName("验证 TextMessage 转译为飞书 text 格式并在尾部注入 at 标签")
    @SuppressWarnings("unchecked")
    void testBuildTextPayload() {
        TextMessage text = TextMessage.of("数据库连接池耗尽")
                .atAll()
                .atUserIds("ou_1234567890");

        Map<String, Object> payload = builder.buildPayload(text);

        assertEquals("text", payload.get("msg_type"));
        Map<String, String> contentObj = (Map<String, String>) payload.get("content");
        String textVal = contentObj.get("text");
        assertTrue(textVal.contains("数据库连接池耗尽"));
        assertTrue(textVal.contains("<at user_id=\"all\">所有人</at>"));
        assertTrue(textVal.contains("<at user_id=\"ou_1234567890\">ou_1234567890</at>"));
    }

    @Test
    @DisplayName("验证 MarkdownMessage 转译为飞书 interactive v2 结构并内嵌 lark_md")
    @SuppressWarnings("unchecked")
    void testBuildMarkdownPayload() {
        MarkdownMessage md = MarkdownMessage.of("每日流水汇总", "**昨日流水**: ¥100,000\n- 笔数: 300")
                .atUserIds("feishu_user_88");

        Map<String, Object> payload = builder.buildPayload(md);

        assertEquals("interactive", payload.get("msg_type"));
        Map<String, Object> card = (Map<String, Object>) payload.get("card");
        assertNotNull(card);

        // Header
        Map<String, Object> header = (Map<String, Object>) card.get("header");
        Map<String, String> titleNode = (Map<String, String>) header.get("title");
        assertEquals("每日流水汇总", titleNode.get("content"));
        assertEquals("blue", header.get("template"));

        // Elements
        List<Map<String, Object>> elements = (List<Map<String, Object>>) card.get("elements");
        assertEquals(1, elements.size());
        Map<String, Object> div = elements.get(0);
        assertEquals("div", div.get("tag"));

        Map<String, String> textObj = (Map<String, String>) div.get("text");
        assertEquals("lark_md", textObj.get("tag"));
        assertTrue(textObj.get("content").contains("**昨日流水**: ¥100,000"));
        assertTrue(textObj.get("content").contains("<at id=\"feishu_user_88\"></at>"));
    }

    @Test
    @DisplayName("验证 CardMessage 转译为飞书完整组件化卡片（主题色映射 + 按钮交互 + 主图）")
    @SuppressWarnings("unchecked")
    void testBuildCardPayload() {
        CardMessage card = CardMessage.of("突发故障告警", "支付网关拒绝率超标")
                .headerColor(CardColor.RED)
                .mainImageUrl("https://img.example.com/danger.png")
                .button("快速止损", "https://ops.example.com/stop", CardButtonType.DANGER)
                .button("查看全链路日志", "https://trace.example.com/123", CardButtonType.PRIMARY)
                .atAll();

        Map<String, Object> payload = builder.buildPayload(card);

        assertEquals("interactive", payload.get("msg_type"));
        Map<String, Object> cardObj = (Map<String, Object>) payload.get("card");

        // Header Color 映射为飞书红色模版
        Map<String, Object> header = (Map<String, Object>) cardObj.get("header");
        assertEquals("red", header.get("template"));

        // Elements
        List<Map<String, Object>> elements = (List<Map<String, Object>>) cardObj.get("elements");
        assertEquals(2, elements.size());

        // Element 0: div 正文与主图
        Map<String, Object> div = elements.get(0);
        Map<String, String> divText = (Map<String, String>) div.get("text");
        assertTrue(divText.get("content").contains("![突发故障告警](https://img.example.com/danger.png)"));
        assertTrue(divText.get("content").contains("支付网关拒绝率超标"));
        assertTrue(divText.get("content").contains("<at id=\"all\"></at>"));

        // Element 1: action 按钮列表
        Map<String, Object> actionEl = elements.get(1);
        assertEquals("action", actionEl.get("tag"));
        List<Map<String, Object>> actions = (List<Map<String, Object>>) actionEl.get("actions");
        assertEquals(2, actions.size());

        assertEquals("danger", actions.get(0).get("type"));
        assertEquals("https://ops.example.com/stop", actions.get(0).get("url"));
        assertEquals("primary", actions.get(1).get("type"));
        assertEquals("https://trace.example.com/123", actions.get(1).get("url"));
    }

    @Test
    @DisplayName("验证 RawMessage 原生透传飞书特化报文")
    void testBuildRawPayload() {
        RawMessage raw = RawMessage.of(Map.of("msg_type", "image", "content", Map.of("image_key", "img_xxx")));
        Map<String, Object> payload = builder.buildPayload(raw);

        assertEquals("image", payload.get("msg_type"));
        assertNotNull(payload.get("content"));
    }
}
