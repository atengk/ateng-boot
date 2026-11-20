package io.github.atengk.boot.notification;

import io.github.atengk.boot.notification.adapter.FeishuPayloadBuilder;
import io.github.atengk.boot.notification.exception.NotificationException;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import io.github.atengk.boot.notification.model.CardButton;
import io.github.atengk.boot.notification.model.CardButtonOrientation;
import io.github.atengk.boot.notification.model.CardButtonType;
import io.github.atengk.boot.notification.model.CardColor;
import io.github.atengk.boot.notification.model.CardMessage;
import io.github.atengk.boot.notification.model.ImageMessage;
import io.github.atengk.boot.notification.model.LinkMessage;
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
    @DisplayName("验证 TextMessage 转译为飞书 text 格式并在尾部注入 at 标签及手机号文本提醒")
    @SuppressWarnings("unchecked")
    void testBuildTextPayload() {
        TextMessage text = TextMessage.of("数据库连接池耗尽")
                .atAll()
                .atMobiles("13800138000")
                .atUserIds("ou_1234567890");

        Map<String, Object> payload = builder.buildPayload(text);

        assertEquals("text", payload.get("msg_type"));
        Map<String, String> contentObj = (Map<String, String>) payload.get("content");
        String textVal = contentObj.get("text");
        assertTrue(textVal.contains("数据库连接池耗尽"));
        assertTrue(textVal.contains("<at user_id=\"all\">所有人</at>"));
        assertTrue(textVal.contains("<at user_id=\"ou_1234567890\">ou_1234567890</at>"));
        assertTrue(textVal.contains("@13800138000"));
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
    @DisplayName("验证仅配置手机号时飞书卡片自适应转译为 @手机号 文本标识且无裸换行")
    @SuppressWarnings("unchecked")
    void testBuildPayloadWithMobileMentions() {
        MarkdownMessage md = MarkdownMessage.of("运维通知", "节点状态异常")
                .atMobiles("13800138000");

        Map<String, Object> payload = builder.buildPayload(md);
        Map<String, Object> card = (Map<String, Object>) payload.get("card");
        List<Map<String, Object>> elements = (List<Map<String, Object>>) card.get("elements");
        Map<String, Object> div = elements.get(0);
        Map<String, String> textObj = (Map<String, String>) div.get("text");
        String content = textObj.get("content");

        assertTrue(content.contains("@13800138000"), "手机号应优雅转译为文本 @手机号");
        assertFalse(content.endsWith("\n\n"), "不得产生孤立未消费的裸换行");
    }

    @Test
    @DisplayName("验证 CardMessage 使用 WATHET 浅天蓝合规映射为飞书官方 wathet 模板")
    @SuppressWarnings("unchecked")
    void testBuildCardPayloadWithWathet() {
        CardMessage wathetCard = CardMessage.of("新版本发布", "v1.2.0 已上线")
                .headerColor(CardColor.WATHET);
        Map<String, Object> wathetPayload = builder.buildPayload(wathetCard);
        Map<String, Object> wathetCardObj = (Map<String, Object>) wathetPayload.get("card");
        Map<String, Object> wathetHeader = (Map<String, Object>) wathetCardObj.get("header");
        assertEquals("wathet", wathetHeader.get("template"));
    }

    @Test
    @DisplayName("验证 RawMessage 原生透传飞书特化报文")
    void testBuildRawPayload() {
        RawMessage raw = RawMessage.of(Map.of("msg_type", "image", "content", Map.of("image_key", "img_xxx")));
        Map<String, Object> payload = builder.buildPayload(raw);

        assertEquals("image", payload.get("msg_type"));
        assertNotNull(payload.get("content"));
    }

    @Test
    @DisplayName("验证 LinkMessage 转译为飞书标准 interactive 交互卡片报文")
    @SuppressWarnings("unchecked")
    void testBuildLinkPayload() {
        LinkMessage link = LinkMessage.of("版本发布通知", "v1.2.0 已顺利发布", "https://feishu.cn/doc/123", "https://img.example.com/banner.png")
                .atAll();

        Map<String, Object> payload = builder.buildPayload(link);

        assertEquals("interactive", payload.get("msg_type"));
        Map<String, Object> card = (Map<String, Object>) payload.get("card");
        assertNotNull(card);

        Map<String, Object> header = (Map<String, Object>) card.get("header");
        assertEquals("blue", header.get("template"));
        Map<String, String> titleNode = (Map<String, String>) header.get("title");
        assertEquals("版本发布通知", titleNode.get("content"));

        List<Map<String, Object>> elements = (List<Map<String, Object>>) card.get("elements");
        assertEquals(2, elements.size());

        // div lark_md element
        Map<String, Object> div = elements.get(0);
        assertEquals("div", div.get("tag"));
        Map<String, String> textObj = (Map<String, String>) div.get("text");
        String content = textObj.get("content");
        assertTrue(content.contains("![版本发布通知](https://img.example.com/banner.png)"));
        assertTrue(content.contains("v1.2.0 已顺利发布"));
        assertTrue(content.contains("<at id=\"all\"></at>"));

        // action button element
        Map<String, Object> action = elements.get(1);
        assertEquals("action", action.get("tag"));
        List<Map<String, Object>> actions = (List<Map<String, Object>>) action.get("actions");
        assertEquals(1, actions.size());
        Map<String, Object> btn = actions.get(0);
        assertEquals("button", btn.get("tag"));
        assertEquals("primary", btn.get("type"));
        assertEquals("https://feishu.cn/doc/123", btn.get("url"));
    }

    @Test
    @DisplayName("验证飞书交互卡片默认开启 wide_screen_mode=true、横排多按钮设置 layout=bisected 且幂等防重 <at> 标签")
    @SuppressWarnings("unchecked")
    void testWideScreenModeBisectedLayoutAndIdempotentAtTags() {
        CardMessage card = CardMessage.of("横向审批卡片", "已联系 <at id=\"ou_999\"></at> 与 <at id=\"all\"></at> 请及时处理")
                .btnOrientation(CardButtonOrientation.HORIZONTAL)
                .button("同意", "https://oa.example.com/yes", CardButtonType.PRIMARY)
                .button("拒绝", "https://oa.example.com/no", CardButtonType.DANGER)
                .atAll()
                .atUserIds("ou_999", "ou_888");

        Map<String, Object> payload = builder.buildPayload(card);
        Map<String, Object> cardObj = (Map<String, Object>) payload.get("card");

        Map<String, Object> config = (Map<String, Object>) cardObj.get("config");
        assertNotNull(config);
        assertEquals(true, config.get("wide_screen_mode"));

        List<Map<String, Object>> elements = (List<Map<String, Object>>) cardObj.get("elements");
        Map<String, Object> div = elements.get(0);
        Map<String, String> textObj = (Map<String, String>) div.get("text");
        String mdContent = textObj.get("content");

        // 已存在的 <at id="all"> 与 <at id="ou_999"> 仅出现 1 次，新增的 <at id="ou_888"> 正常追加
        assertEquals(1, mdContent.split("<at id=\"all\"></at>", -1).length - 1);
        assertEquals(1, mdContent.split("<at id=\"ou_999\"></at>", -1).length - 1);
        assertTrue(mdContent.contains("<at id=\"ou_888\"></at>"));

        Map<String, Object> actionEl = elements.get(1);
        assertEquals("bisected", actionEl.get("layout"));
    }

    @Test
    @DisplayName("验证 ImageMessage 在飞书支持原生 imageKey 与公网 imageUrl 自适应图文卡片")
    @SuppressWarnings("unchecked")
    void testBuildImagePayload() {
        ImageMessage keyImg = ImageMessage.ofFeishuKey("img_v2_test_key");
        Map<String, Object> keyPayload = builder.buildPayload(keyImg);
        assertEquals("image", keyPayload.get("msg_type"));
        Map<String, Object> content = (Map<String, Object>) keyPayload.get("content");
        assertEquals("img_v2_test_key", content.get("image_key"));

        ImageMessage urlImg = ImageMessage.ofUrl("https://img.example.com/feishu.png");
        Map<String, Object> urlPayload = builder.buildPayload(urlImg);
        assertEquals("interactive", urlPayload.get("msg_type"));
        Map<String, Object> card = (Map<String, Object>) urlPayload.get("card");
        List<Map<String, Object>> elements = (List<Map<String, Object>>) card.get("elements");
        Map<String, String> divText = (Map<String, String>) elements.get(0).get("text");
        assertTrue(divText.get("content").contains("https://img.example.com/feishu.png"));

        ImageMessage base64Only = ImageMessage.ofBase64("aGVsbG8=", "5d41402abc4b2a76b9719d911017c592");
        NotificationException ex = assertThrows(NotificationException.class, () -> builder.buildPayload(base64Only));
        assertEquals(NotificationResultCode.NOTIFICATION_UNSUPPORTED_PLATFORM, ex.getResultCode());
    }
}
