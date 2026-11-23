package io.github.atengk.boot.notification.model;

import io.github.atengk.boot.notification.exception.NotificationException;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 轻量图文链接通知领域消息模型
 * 统一跨端转译为钉钉 Link、企微 News 单图文与飞书 Interactive 卡片
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Data
@NoArgsConstructor
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class LinkMessage extends NotificationMessage {

    private static final long serialVersionUID = 1L;

    /**
     * 消息标题
     */
    private String title;

    /**
     * 消息正文或描述文本
     */
    private String text;

    /**
     * 点击后跳转的完整 URL 地址
     */
    private String messageUrl;

    /**
     * 图片封面完整 URL 地址（可选）
     */
    private String picUrl;

    public LinkMessage(String title, String messageUrl) {
        this(title, null, messageUrl, null);
    }

    public LinkMessage(String title, String text, String messageUrl) {
        this(title, text, messageUrl, null);
    }

    public LinkMessage(String title, String text, String messageUrl, String picUrl) {
        this.title = title;
        this.text = text;
        this.messageUrl = messageUrl;
        this.picUrl = picUrl;
    }

    public static LinkMessage of(String title, String messageUrl) {
        return new LinkMessage(title, messageUrl);
    }

    public static LinkMessage of(String title, String text, String messageUrl) {
        return new LinkMessage(title, text, messageUrl);
    }

    public static LinkMessage of(String title, String text, String messageUrl, String picUrl) {
        return new LinkMessage(title, text, messageUrl, picUrl);
    }

    public LinkMessage title(String title) {
        this.title = title;
        return this;
    }

    public LinkMessage text(String text) {
        this.text = text;
        return this;
    }

    public LinkMessage messageUrl(String messageUrl) {
        this.messageUrl = messageUrl;
        return this;
    }

    public LinkMessage picUrl(String picUrl) {
        this.picUrl = picUrl;
        return this;
    }

    @Override
    public MessageType getMessageType() {
        return MessageType.LINK;
    }

    @Override
    public void validate() {
        if (title == null || title.isBlank()) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_MESSAGE_EMPTY, "图文链接消息标题不能为空");
        }
        if (messageUrl == null || messageUrl.isBlank()) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_MESSAGE_EMPTY, "图文链接消息跳转 URL 不能为空");
        }
    }
}
