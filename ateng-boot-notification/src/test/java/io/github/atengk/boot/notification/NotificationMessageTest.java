package io.github.atengk.boot.notification;

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
import io.github.atengk.boot.notification.model.MentionTarget;
import io.github.atengk.boot.notification.model.MessageType;
import io.github.atengk.boot.notification.model.RawMessage;
import io.github.atengk.boot.notification.model.TextMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 四阶领域消息模型与统一 @人 抽象单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
class NotificationMessageTest {

    @Test
    @DisplayName("验证 TextMessage 纯文本消息构建与空校验")
    void testTextMessage() {
        TextMessage msg = TextMessage.of("系统告警")
                .channel("ops-alert")
                .atAll()
                .atMobiles("13800000001", "13800000002")
                .atUserIds("user_01");

        assertEquals(MessageType.TEXT, msg.getMessageType());
        assertEquals("系统告警", msg.getContent());
        assertEquals("ops-alert", msg.getChannel());
        assertTrue(msg.getMention().isAtAll());
        assertTrue(msg.getMention().getAtMobiles().contains("13800000001"));
        assertTrue(msg.getMention().getAtUserIds().contains("user_01"));

        assertDoesNotThrow(msg::validate);

        TextMessage emptyMsg = new TextMessage("   ");
        NotificationException ex = assertThrows(NotificationException.class, emptyMsg::validate);
        assertEquals(NotificationResultCode.NOTIFICATION_MESSAGE_EMPTY.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("验证 MarkdownMessage 富文本消息构建与空校验")
    void testMarkdownMessage() {
        MarkdownMessage msg = MarkdownMessage.of("构建通知", "### CI/CD 构建成功\n- 分支: `dev`")
                .atMobiles("13911112222");

        assertEquals(MessageType.MARKDOWN, msg.getMessageType());
        assertEquals("构建通知", msg.getTitle());
        assertTrue(msg.getContent().contains("CI/CD 构建成功"));
        assertFalse(msg.getMention().isAtAll());
        assertTrue(msg.getMention().getAtMobiles().contains("13911112222"));

        assertDoesNotThrow(msg::validate);

        MarkdownMessage emptyMsg = new MarkdownMessage(null);
        NotificationException ex = assertThrows(NotificationException.class, emptyMsg::validate);
        assertEquals(NotificationResultCode.NOTIFICATION_MESSAGE_EMPTY.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("验证 CardMessage 统一交互卡片单行动与多按钮决策")
    void testCardMessage() {
        CardMessage singleCard = CardMessage.of("单行动卡片", "审批工单待处理")
                .headerColor(CardColor.RED)
                .singleAction("立即审批", "https://oa.example.com/approve/123");

        assertEquals(MessageType.CARD, singleCard.getMessageType());
        assertEquals(CardColor.RED, singleCard.getHeaderColor());
        assertTrue(singleCard.isSingleAction());
        assertEquals("立即审批", singleCard.getSingleActionText());
        assertEquals("https://oa.example.com/approve/123", singleCard.getSingleActionUrl());

        CardMessage multiCard = CardMessage.of("多按钮卡片", "发布版本待确认")
                .headerColor(CardColor.GREEN)
                .button("通过", "https://ci.example.com/pass", CardButtonType.PRIMARY)
                .button("驳回", "https://ci.example.com/reject", CardButtonType.DANGER)
                .mainImageUrl("https://img.example.com/cover.png");

        assertFalse(multiCard.isSingleAction());
        assertEquals(2, multiCard.getButtons().size());
        assertEquals("https://img.example.com/cover.png", multiCard.getMainImageUrl());

        assertDoesNotThrow(multiCard::validate);

        CardMessage emptyCard = new CardMessage("   ", "   ");
        NotificationException ex = assertThrows(NotificationException.class, emptyCard::validate);
        assertEquals(NotificationResultCode.NOTIFICATION_MESSAGE_EMPTY.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("验证 CardMessage 按钮排版方向 btnOrientation 默认为 VERTICAL 且支持切换为 HORIZONTAL")
    void testCardMessageButtonOrientation() {
        CardMessage defaultCard = CardMessage.of("标题", "内容");
        assertEquals(CardButtonOrientation.VERTICAL, defaultCard.getBtnOrientation());

        CardMessage horizontalCard = CardMessage.of("审批", "请审批")
                .btnOrientation(CardButtonOrientation.HORIZONTAL);
        assertEquals(CardButtonOrientation.HORIZONTAL, horizontalCard.getBtnOrientation());

        // 空安全保底
        horizontalCard.btnOrientation(null);
        assertEquals(CardButtonOrientation.VERTICAL, horizontalCard.getBtnOrientation());
    }

    @Test
    @DisplayName("验证 LinkMessage 图文链接消息模型构建、校验与 @人 扩展")
    void testLinkMessage() {
        LinkMessage link = LinkMessage.of("版本发布", "v1.2.0 已上线", "https://example.com/v1.2.0", "https://img.example.com/cover.png")
                .channel("ops-link")
                .atAll()
                .atMobiles("13800000001");

        assertEquals(MessageType.LINK, link.getMessageType());
        assertEquals("版本发布", link.getTitle());
        assertEquals("v1.2.0 已上线", link.getText());
        assertEquals("https://example.com/v1.2.0", link.getMessageUrl());
        assertEquals("https://img.example.com/cover.png", link.getPicUrl());
        assertEquals("ops-link", link.getChannel());
        assertTrue(link.getMention().isAtAll());
        assertTrue(link.getMention().getAtMobiles().contains("13800000001"));

        assertDoesNotThrow(link::validate);

        // 校验异常分支：title 为空
        LinkMessage noTitle = LinkMessage.of("   ", "https://example.com");
        NotificationException ex1 = assertThrows(NotificationException.class, noTitle::validate);
        assertEquals(NotificationResultCode.NOTIFICATION_MESSAGE_EMPTY.getCode(), ex1.getCode());

        // 校验异常分支：messageUrl 为空
        LinkMessage noUrl = LinkMessage.of("标题", "   ");
        NotificationException ex2 = assertThrows(NotificationException.class, noUrl::validate);
        assertEquals(NotificationResultCode.NOTIFICATION_MESSAGE_EMPTY.getCode(), ex2.getCode());
    }

    @Test
    @DisplayName("验证 ImageMessage 字节数组自动计算 Base64 与 MD5 校验和")
    void testImageMessage() {
        byte[] dummyData = "fake-png-binary-stream-123456".getBytes(StandardCharsets.UTF_8);
        ImageMessage img = ImageMessage.fromBytes(dummyData);

        assertEquals(MessageType.IMAGE, img.getMessageType());
        assertNotNull(img.getBase64());
        assertNotNull(img.getMd5());
        assertEquals(32, img.getMd5().length(), "MD5 必须为 32 位 Hex 字符串");

        assertDoesNotThrow(img::validate);

        ImageMessage emptyImg = new ImageMessage();
        NotificationException ex = assertThrows(NotificationException.class, emptyImg::validate);
        assertEquals(NotificationResultCode.NOTIFICATION_MESSAGE_EMPTY.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("验证 ImageMessage 支持从本地 File 和 Path 自动计算 MD5 和 Base64")
    void testImageMessageFromFileAndFromPath(@TempDir Path tempDir) throws IOException {
        Path imagePath = tempDir.resolve("test-image.png");
        byte[] content = "sample-image-binary-data-for-test".getBytes(StandardCharsets.UTF_8);
        Files.write(imagePath, content);

        // 1. 验证 fromFile
        File imageFile = imagePath.toFile();
        ImageMessage imgFromFile = ImageMessage.fromFile(imageFile);
        assertNotNull(imgFromFile.getBase64());
        assertNotNull(imgFromFile.getMd5());
        assertEquals(32, imgFromFile.getMd5().length());
        assertDoesNotThrow(imgFromFile::validate);

        // 2. 验证 fromPath
        ImageMessage imgFromPath = ImageMessage.fromPath(imagePath);
        assertEquals(imgFromFile.getBase64(), imgFromPath.getBase64());
        assertEquals(imgFromFile.getMd5(), imgFromPath.getMd5());

        // 3. 验证异常分支：文件不存在
        File nonExistentFile = tempDir.resolve("non-existent.png").toFile();
        assertThrows(NotificationException.class, () -> ImageMessage.fromFile(nonExistentFile));
        assertThrows(NotificationException.class, () -> ImageMessage.fromPath(nonExistentFile.toPath()));
        assertThrows(NotificationException.class, () -> ImageMessage.fromFile(null));
        assertThrows(NotificationException.class, () -> ImageMessage.fromPath(null));
    }

    @Test
    @DisplayName("验证 ImageMessage 支持从 InputStream 流构造并自动计算 MD5 和 Base64")
    void testImageMessageFromInputStream() throws IOException {
        byte[] content = "sample-stream-image-binary-data".getBytes(StandardCharsets.UTF_8);

        // 1. 验证 fromInputStream
        try (java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(content)) {
            ImageMessage msg = ImageMessage.fromInputStream(in);
            assertNotNull(msg.getBase64());
            assertNotNull(msg.getMd5());
            assertEquals(32, msg.getMd5().length());
            assertDoesNotThrow(msg::validate);
        }

        // 2. 验证 fromStream 别名
        try (java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(content)) {
            ImageMessage msg = ImageMessage.fromStream(in);
            assertNotNull(msg.getBase64());
            assertNotNull(msg.getMd5());
        }

        // 3. 验证空流与 null 异常分支
        assertThrows(NotificationException.class, () -> ImageMessage.fromInputStream(null));
        assertThrows(NotificationException.class, () -> ImageMessage.fromStream(null));
        try (java.io.ByteArrayInputStream emptyIn = new java.io.ByteArrayInputStream(new byte[0])) {
            assertThrows(NotificationException.class, () -> ImageMessage.fromInputStream(emptyIn));
        }
    }

    @Test
    @DisplayName("验证 RawMessage 原生特化逃生舱支持字符串与 Map 报文")
    void testRawMessage() {
        RawMessage jsonRaw = RawMessage.of("{\"msgtype\":\"text\",\"text\":{\"content\":\"raw\"}}");
        assertEquals(MessageType.RAW, jsonRaw.getMessageType());
        assertDoesNotThrow(jsonRaw::validate);
        assertEquals("text", jsonRaw.toPayloadMap().get("msgtype"));

        RawMessage mapRaw = RawMessage.of(Map.of("custom_key", "custom_value"));
        assertEquals(MessageType.RAW, mapRaw.getMessageType());
        assertDoesNotThrow(mapRaw::validate);
        assertEquals("custom_value", mapRaw.toPayloadMap().get("custom_key"));

        RawMessage emptyRaw = new RawMessage();
        assertEquals(0, emptyRaw.toPayloadMap().size());
        NotificationException ex = assertThrows(NotificationException.class, emptyRaw::validate);
        assertEquals(NotificationResultCode.NOTIFICATION_MESSAGE_EMPTY.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("验证 MentionTarget 组合 @人 行为契约")
    void testMentionTarget() {
        MentionTarget target = new MentionTarget();
        assertTrue(target.isEmpty());
        assertFalse(target.hasMentions());

        target.atAll().addMobiles("13800000001").addMobiles(List.of("13800000002")).addUserIds("user_a", "user_b");

        assertFalse(target.isEmpty());
        assertTrue(target.hasMentions());
        assertTrue(target.isAtAll());
        assertEquals(2, target.getAtMobiles().size());
        assertEquals(2, target.getAtUserIds().size());
    }
}
