package io.github.atengk.boot.notification;

import io.github.atengk.boot.notification.adapter.WeComPayloadBuilder;
import io.github.atengk.boot.notification.exception.NotificationException;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import io.github.atengk.boot.notification.model.CardButton;
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
    @DisplayName("验证 CardMessage 主题色自适应映射为企微 source.desc_color")
    @SuppressWarnings("unchecked")
    void testBuildTemplateCardHeaderColor() {
        CardMessage redCard = CardMessage.of("系统严重故障", "数据库不可用")
                .headerColor(CardColor.RED);
        Map<String, Object> redPayload = builder.buildPayload(redCard);
        Map<String, Object> redCardObj = (Map<String, Object>) redPayload.get("template_card");
        Map<String, Object> redSource = (Map<String, Object>) redCardObj.get("source");
        assertEquals(2, redSource.get("desc_color"));

        CardMessage greenCard = CardMessage.of("发布成功", "v1.0 已上线")
                .headerColor(CardColor.GREEN);
        Map<String, Object> greenPayload = builder.buildPayload(greenCard);
        Map<String, Object> greenCardObj = (Map<String, Object>) greenPayload.get("template_card");
        Map<String, Object> greenSource = (Map<String, Object>) greenCardObj.get("source");
        assertEquals(3, greenSource.get("desc_color"));

        CardMessage greyCard = CardMessage.of("普通通知", "日终结算已完成")
                .headerColor(CardColor.GREY);
        Map<String, Object> greyPayload = builder.buildPayload(greyCard);
        Map<String, Object> greyCardObj = (Map<String, Object>) greyPayload.get("template_card");
        Map<String, Object> greySource = (Map<String, Object>) greyCardObj.get("source");
        assertEquals(0, greySource.get("desc_color"));
    }

    @Test
    @DisplayName("验证 RawMessage 原生透传企微特化报文")
    void testBuildRawPayload() {
        RawMessage raw = RawMessage.of("{\"msgtype\":\"news\",\"news\":{\"articles\":[]}}");
        Map<String, Object> payload = builder.buildPayload(raw);

        assertEquals("news", payload.get("msgtype"));
        assertNotNull(payload.get("news"));
    }

    @Test
    @DisplayName("验证 LinkMessage 转译为企业微信原生 news 单图文报文格式")
    @SuppressWarnings("unchecked")
    void testBuildLinkPayload() {
        LinkMessage link = LinkMessage.of("中秋节礼品领取", "今年中秋节公司有豪礼相送", "https://work.weixin.qq.com/gift", "https://img.example.com/gift.png");

        Map<String, Object> payload = builder.buildPayload(link);

        assertEquals("news", payload.get("msgtype"));
        Map<String, Object> newsObj = (Map<String, Object>) payload.get("news");
        assertNotNull(newsObj);
        List<Map<String, Object>> articles = (List<Map<String, Object>>) newsObj.get("articles");
        assertEquals(1, articles.size());
        Map<String, Object> article = articles.get(0);
        assertEquals("中秋节礼品领取", article.get("title"));
        assertEquals("今年中秋节公司有豪礼相送", article.get("description"));
        assertEquals("https://work.weixin.qq.com/gift", article.get("url"));
        assertEquals("https://img.example.com/gift.png", article.get("picurl"));
    }

    @Test
    @DisplayName("验证企微无按钮与多按钮 template_card 强制生成必填 card_action 且 jump_list 裁剪至 3 项")
    @SuppressWarnings("unchecked")
    void testBuildTemplateCardRequiredCardActionAndJumpListCap() {
        // 1. 无按钮卡片仍需生成必填 card_action (type=1 + 合规兜底 url)
        CardMessage noBtnCard = CardMessage.of("系统预警", "Redis 内存水位达 88%");
        Map<String, Object> noBtnPayload = builder.buildPayload(noBtnCard);
        Map<String, Object> noBtnCardObj = (Map<String, Object>) noBtnPayload.get("template_card");
        Map<String, Object> noBtnAction = (Map<String, Object>) noBtnCardObj.get("card_action");
        assertNotNull(noBtnAction);
        assertEquals(1, noBtnAction.get("type"));
        assertEquals("https://work.weixin.qq.com", noBtnAction.get("url"));

        // 2. 多按钮（4 个按钮）自动以首按钮 URL 补齐必填 card_action 且 jump_list 最多保留前 3 项
        CardMessage multiBtnCard = CardMessage.of("发布审批", "请确认生产变更")
                .button("按钮1", "https://ops.example.com/1")
                .button("按钮2", "https://ops.example.com/2")
                .button("按钮3", "https://ops.example.com/3")
                .button("按钮4", "https://ops.example.com/4");
        Map<String, Object> multiPayload = builder.buildPayload(multiBtnCard);
        Map<String, Object> multiCardObj = (Map<String, Object>) multiPayload.get("template_card");

        Map<String, Object> multiAction = (Map<String, Object>) multiCardObj.get("card_action");
        assertNotNull(multiAction);
        assertEquals(1, multiAction.get("type"));
        assertEquals("https://ops.example.com/1", multiAction.get("url"));

        List<Map<String, Object>> jumpList = (List<Map<String, Object>>) multiCardObj.get("jump_list");
        assertEquals(3, jumpList.size());
        assertEquals("按钮3", jumpList.get(2).get("title"));
    }

    @Test
    @DisplayName("验证企微 news_notice 图文卡片将正文写入 main_title.desc 对齐官方数据结构")
    @SuppressWarnings("unchecked")
    void testNewsNoticeMapsContentToMainTitleDesc() {
        CardMessage newsCard = CardMessage.of("周报封面", "本周核心 SLA 达到 99.99%")
                .mainImageUrl("https://img.example.com/cover.png")
                .singleAction("阅读周报", "https://report.example.com/1");

        Map<String, Object> payload = builder.buildPayload(newsCard);
        Map<String, Object> cardObj = (Map<String, Object>) payload.get("template_card");
        assertEquals("news_notice", cardObj.get("card_type"));

        Map<String, Object> mainTitle = (Map<String, Object>) cardObj.get("main_title");
        assertEquals("周报封面", mainTitle.get("title"));
        assertEquals("本周核心 SLA 达到 99.99%", mainTitle.get("desc"));
        assertNull(cardObj.get("sub_title_text"));
    }

    @Test
    @DisplayName("验证 ImageMessage 在企微支持 imageUrl 自适应 news 图文及无 md5 自动补算")
    @SuppressWarnings("unchecked")
    void testImageUrlAndAutoMd5() {
        ImageMessage urlImg = ImageMessage.ofUrl("https://img.example.com/wecom-banner.png");
        Map<String, Object> urlPayload = builder.buildPayload(urlImg);
        assertEquals("news", urlPayload.get("msgtype"));
        Map<String, Object> newsNode = (Map<String, Object>) urlPayload.get("news");
        List<Map<String, Object>> articles = (List<Map<String, Object>>) newsNode.get("articles");
        assertEquals("https://img.example.com/wecom-banner.png", articles.get(0).get("picurl"));

        ImageMessage base64Only = ImageMessage.ofBase64("aGVsbG8=", null);
        Map<String, Object> imgPayload = builder.buildPayload(base64Only);
        assertEquals("image", imgPayload.get("msgtype"));
        Map<String, String> imageNode = (Map<String, String>) imgPayload.get("image");
        assertEquals("5d41402abc4b2a76b9719d911017c592", imageNode.get("md5"));

        // 非法 Base64 且缺少 md5 时应抛出 NOTIFICATION_PARAMETER_INVALID
        ImageMessage badBase64 = ImageMessage.ofBase64("not!valid!base64", null);
        NotificationException badB64Ex = assertThrows(NotificationException.class, () -> builder.buildPayload(badBase64));
        assertEquals(NotificationResultCode.NOTIFICATION_PARAMETER_INVALID, badB64Ex.getResultCode());

        // 仅含飞书 imageKey 的跨平台消息传入企微应统一抛出 NOTIFICATION_UNSUPPORTED_PLATFORM
        ImageMessage feishuOnly = ImageMessage.ofFeishuKey("img_v2_only_feishu");
        NotificationException unsupportedEx = assertThrows(NotificationException.class, () -> builder.buildPayload(feishuOnly));
        assertEquals(NotificationResultCode.NOTIFICATION_UNSUPPORTED_PLATFORM, unsupportedEx.getResultCode());
    }
}
