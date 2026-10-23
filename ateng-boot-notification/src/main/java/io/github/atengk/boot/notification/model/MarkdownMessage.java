package io.github.atengk.boot.notification.model;

import io.github.atengk.boot.notification.exception.NotificationException;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Markdown 富文本通知领域消息模型
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
@NoArgsConstructor
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class MarkdownMessage extends NotificationMessage {

    private static final long serialVersionUID = 1L;

    /**
     * 消息标题（钉钉、飞书等在会话列表展示时作为摘要标题）
     */
    private String title;

    /**
     * Markdown 正文内容
     */
    private String content;

    public MarkdownMessage(String content) {
        this.content = content;
    }

    public MarkdownMessage(String title, String content) {
        this.title = title;
        this.content = content;
    }

    public static MarkdownMessage of(String content) {
        return new MarkdownMessage(content);
    }

    public static MarkdownMessage of(String title, String content) {
        return new MarkdownMessage(title, content);
    }

    public MarkdownMessage title(String title) {
        this.title = title;
        return this;
    }

    public MarkdownMessage content(String content) {
        this.content = content;
        return this;
    }

    @Override
    public MessageType getMessageType() {
        return MessageType.MARKDOWN;
    }

    @Override
    public void validate() {
        if (content == null || content.isBlank()) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_MESSAGE_EMPTY, "Markdown 通知正文内容不能为空");
        }
    }
}
