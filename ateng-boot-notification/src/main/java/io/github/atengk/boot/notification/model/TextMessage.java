package io.github.atengk.boot.notification.model;

import io.github.atengk.boot.notification.exception.NotificationException;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 纯文本通知领域消息模型
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
@NoArgsConstructor
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class TextMessage extends NotificationMessage {

    private static final long serialVersionUID = 1L;

    /**
     * 纯文本正文内容
     */
    private String content;

    public TextMessage(String content) {
        this.content = content;
    }

    public TextMessage(String content, MentionTarget mention) {
        this.content = content;
        setMention(mention);
    }

    public static TextMessage of(String content) {
        return new TextMessage(content);
    }

    public TextMessage content(String content) {
        this.content = content;
        return this;
    }

    @Override
    public MessageType getMessageType() {
        return MessageType.TEXT;
    }

    @Override
    public void validate() {
        if (content == null || content.isBlank()) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_MESSAGE_EMPTY, "纯文本通知正文内容不能为空");
        }
    }
}
