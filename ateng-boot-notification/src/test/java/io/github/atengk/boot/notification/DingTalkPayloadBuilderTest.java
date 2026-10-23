package io.github.atengk.boot.notification;

import io.github.atengk.boot.notification.adapter.DingTalkPayloadBuilder;
import io.github.atengk.boot.notification.model.CardButton;
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
 * 钉钉群机器人报文构建适配器单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
class DingTalkPayloadBuilderTest {

    private final DingTalkPayloadBuilder builder = new DingTalkPayloadBuilder();

    @Test
    @DisplayName("验证 TextMessage 转译为钉钉 text 报文并携带 at 节点")
    @SuppressWarnings("unchecked")
    void testBuildTextPayload() {
        TextMessage text = TextMessage.of("CPU 负载超过 90%")
                .atAll()
                .atMobiles("13800000001")
                .atUserIds("ding_user_123");

        Map<String, Object> payload = builder.buildPayload(text);

        assertEquals("text", payload.get("msgtype"));
        Map<String, Object> textObj = (Map<String, Object>) payload.get("text");
        assertEquals("CPU 负载超过 90%", textObj.get("content"));

        Map<String, Object> atObj = (Map<String, Object>) payload.get("at");
        assertNotNull(atObj);
        assertEquals(true, atObj.get("isAtAll"));
        assertEquals(List.of("13800000001"), atObj.get("atMobiles"));
        assertEquals(List.of("ding_user_123"), atObj.get("atUserIds"));
    }

    @Test
    @DisplayName("验证 MarkdownMessage 转译为钉钉 markdown 报文")
    @SuppressWarnings("unchecked")
    void testBuildMarkdownPayload() {
        MarkdownMessage md = MarkdownMessage.of("构建告警", "### 编译失败\n- 模块: `core`")
                .atMobiles("13900000000");

        Map<String, Object> payload = builder.buildPayload(md);

        assertEquals("markdown", payload.get("msgtype"));
        Map<String, Object> mdObj = (Map<String, Object>) payload.get("markdown");
        assertEquals("构建告警", mdObj.get("title"));
        assertEquals("### 编译失败\n- 模块: `core`", mdObj.get("text"));

        Map<String, Object> atObj = (Map<String, Object>) payload.get("at");
        assertNotNull(atObj);
        assertEquals(false, atObj.get("isAtAll"));
        assertEquals(List.of("13900000000"), atObj.get("atMobiles"));
    }

    @Test
    @DisplayName("验证 CardMessage 具备单跳转行动时转译为钉钉 singleAction actionCard")
    @SuppressWarnings("unchecked")
    void testBuildSingleActionCardPayload() {
        CardMessage card = CardMessage.of("发布单待确认", "v1.2.0 发布申请已提交")
                .singleAction("进入控制台审批", "https://ops.example.com/deploy/1");

        Map<String, Object> payload = builder.buildPayload(card);

        assertEquals("actionCard", payload.get("msgtype"));
        Map<String, Object> actionCard = (Map<String, Object>) payload.get("actionCard");
        assertEquals("发布单待确认", actionCard.get("title"));
        assertEquals("v1.2.0 发布申请已提交", actionCard.get("text"));
        assertEquals("进入控制台审批", actionCard.get("singleTitle"));
        assertEquals("https://ops.example.com/deploy/1", actionCard.get("singleURL"));
        assertNull(actionCard.get("btns"));
    }

    @Test
    @DisplayName("验证 CardMessage 多按钮时转译为钉钉 btns 列表 actionCard 并内嵌主图")
    @SuppressWarnings("unchecked")
    void testBuildMultiButtonActionCardPayload() {
        CardMessage card = CardMessage.of("P0 故障通报", "核心支付服务响应超时")
                .mainImageUrl("https://img.example.com/alert.png")
                .button(CardButton.of("查看监控", "https://monitor.example.com"))
                .button(CardButton.of("处理故障", "https://ops.example.com"));

        Map<String, Object> payload = builder.buildPayload(card);

        assertEquals("actionCard", payload.get("msgtype"));
        Map<String, Object> actionCard = (Map<String, Object>) payload.get("actionCard");
        assertEquals("P0 故障通报", actionCard.get("title"));
        assertTrue(((String) actionCard.get("text")).contains("![P0 故障通报](https://img.example.com/alert.png)"));
        assertTrue(((String) actionCard.get("text")).contains("核心支付服务响应超时"));

        assertEquals("0", actionCard.get("btnOrientation"));
        List<Map<String, String>> btns = (List<Map<String, String>>) actionCard.get("btns");
        assertEquals(2, btns.size());
        assertEquals("查看监控", btns.get(0).get("title"));
        assertEquals("https://monitor.example.com", btns.get(0).get("actionURL"));
    }

    @Test
    @DisplayName("验证 RawMessage 原生透传钉钉自定义报文")
    void testBuildRawPayload() {
        RawMessage raw = RawMessage.of(Map.of("msgtype", "feedCard", "feedCard", Map.of("links", List.of())));
        Map<String, Object> payload = builder.buildPayload(raw);

        assertEquals("feedCard", payload.get("msgtype"));
        assertNotNull(payload.get("feedCard"));
    }
}
