package io.github.atengk.boot.notification;

import io.github.atengk.boot.notification.exception.NotificationException;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import io.github.atengk.boot.notification.model.CardButton;
import io.github.atengk.boot.notification.model.CardButtonType;
import io.github.atengk.boot.notification.model.CardColor;
import io.github.atengk.boot.notification.model.CardMessage;
import io.github.atengk.boot.notification.model.ImageMessage;
import io.github.atengk.boot.notification.model.MarkdownMessage;
import io.github.atengk.boot.notification.model.MentionTarget;
import io.github.atengk.boot.notification.model.MessageType;
import io.github.atengk.boot.notification.model.RawMessage;
import io.github.atengk.boot.notification.model.TextMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
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
