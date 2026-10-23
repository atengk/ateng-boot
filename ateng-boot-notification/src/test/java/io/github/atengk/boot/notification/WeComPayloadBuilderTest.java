package io.github.atengk.boot.notification;

import io.github.atengk.boot.notification.adapter.WeComPayloadBuilder;
import io.github.atengk.boot.notification.model.CardButton;
import io.github.atengk.boot.notification.model.CardMessage;
import io.github.atengk.boot.notification.model.ImageMessage;
import io.github.atengk.boot.notification.model.MarkdownMessage;
import io.github.atengk.boot.notification.model.RawMessage;
import io.github.atengk.boot.notification.model.TextMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 企业微信群机器人报文构建适配器单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
class WeComPayloadBuilderTest {

    private final WeComPayloadBuilder builder = new WeComPayloadBuilder();

    @Test
    @DisplayName("验证 TextMessage 转译为企微 text 格式并分流工号与手机号")
    @SuppressWarnings("unchecked")
    void testBuildTextPayload() {
        TextMessage text = TextMessage.of("服务器磁盘告警 > 85%")
                .atAll()
                .atUserIds("zhangsan")
                .atMobiles("13812345678");

        Map<String, Object> payload = builder.buildPayload(text);

        assertEquals("text", payload.get("msgtype"));
        Map<String, Object> textObj = (Map<String, Object>) payload.get("text");
        assertEquals("服务器磁盘告警 > 85%", textObj.get("content"));

        List<String> userList = (List<String>) textObj.get("mentioned_list");
        assertTrue(userList.contains("zhangsan"));
        assertTrue(userList.contains("@all"));

        List<String> mobileList = (List<String>) textObj.get("mentioned_mobile_list");
        assertEquals(List.of("13812345678"), mobileList);
    }

    @Test
    @DisplayName("验证 MarkdownMessage 转译为企微 markdown 格式并自动安全清洗表格与注入 @ 标签")
    @SuppressWarnings("unchecked")
    void testBuildMarkdownPayload() {
        String content = """
                ### 对账单
                | 批次 | 金额 |
                |---|---|
                | B001 | ¥100 |
                """;
        MarkdownMessage md = MarkdownMessage.of("对账通知", content)
                .atAll()
                .atUserIds("lisi");

        Map<String, Object> payload = builder.buildPayload(md);

        assertEquals("markdown", payload.get("msgtype"));
        Map<String, Object> mdObj = (Map<String, Object>) payload.get("markdown");
        String finalContent = (String) mdObj.get("content");

        // 表格已被安全转译为引用列表
        assertTrue(finalContent.contains("> **批次**: B001 | **金额**: ¥100"));
        // 企微专用 @ 标签已注入
        assertTrue(finalContent.contains("<@all>"));
        assertTrue(finalContent.contains("<@lisi>"));
    }

    @Test
    @DisplayName("验证 CardMessage 映射为企微 template_card (无图片映射为 text_notice)")
    @SuppressWarnings("unchecked")
    void testBuildTemplateCardWithoutImage() {
        CardMessage card = CardMessage.of("待审批提醒", "加班申请待处理")
                .button(CardButton.of("查看申请", "https://oa.example.com/item/1"));

        Map<String, Object> payload = builder.buildPayload(card);

        assertEquals("template_card", payload.get("msgtype"));
        Map<String, Object> cardObj = (Map<String, Object>) payload.get("template_card");
        assertEquals("text_notice", cardObj.get("card_type"));

        Map<String, String> mainTitle = (Map<String, String>) cardObj.get("main_title");
        assertEquals("待审批提醒", mainTitle.get("title"));
        assertEquals("加班申请待处理", cardObj.get("sub_title_text"));

        List<Map<String, Object>> jumpList = (List<Map<String, Object>>) cardObj.get("jump_list");
        assertEquals(1, jumpList.size());
        assertEquals("查看申请", jumpList.get(0).get("title"));
        assertEquals("https://oa.example.com/item/1", jumpList.get(0).get("url"));
    }

    @Test
    @DisplayName("验证 CardMessage 包含主图时自动转译为企微 news_notice 卡片")
    @SuppressWarnings("unchecked")
    void testBuildTemplateCardWithImage() {
        CardMessage card = CardMessage.of("运营周报", "本周增长分析")
                .mainImageUrl("https://img.example.com/weekly.png")
                .singleAction("阅读全文", "https://report.example.com/weekly");

        Map<String, Object> payload = builder.buildPayload(card);

        assertEquals("template_card", payload.get("msgtype"));
        Map<String, Object> cardObj = (Map<String, Object>) payload.get("template_card");
        assertEquals("news_notice", cardObj.get("card_type"));

        Map<String, Object> imageNode = (Map<String, Object>) cardObj.get("card_image");
        assertNotNull(imageNode);
        assertEquals("https://img.example.com/weekly.png", imageNode.get("url"));

        Map<String, Object> cardAction = (Map<String, Object>) cardObj.get("card_action");
        assertNotNull(cardAction);
        assertEquals("https://report.example.com/weekly", cardAction.get("url"));
    }

    @Test
    @DisplayName("验证 ImageMessage 转译为企微 image 报文格式")
    @SuppressWarnings("unchecked")
    void testBuildImagePayload() {
        ImageMessage img = new ImageMessage("aGVsbG8=", "5d41402abc4b2a76b9719d911017c592");
        Map<String, Object> payload = builder.buildPayload(img);

        assertEquals("image", payload.get("msgtype"));
        Map<String, String> imageObj = (Map<String, String>) payload.get("image");
        assertEquals("aGVsbG8=", imageObj.get("base64"));
        assertEquals("5d41402abc4b2a76b9719d911017c592", imageObj.get("md5"));
    }

    @Test
    @DisplayName("验证 RawMessage 原生透传企微特化报文")
    void testBuildRawPayload() {
        RawMessage raw = RawMessage.of("{\"msgtype\":\"news\",\"news\":{\"articles\":[]}}");
        Map<String, Object> payload = builder.buildPayload(raw);

        assertEquals("news", payload.get("msgtype"));
        assertNotNull(payload.get("news"));
    }
}
