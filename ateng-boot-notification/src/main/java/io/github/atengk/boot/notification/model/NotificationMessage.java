package io.github.atengk.boot.notification.model;

import lombok.Data;

import java.io.Serializable;
import java.util.Collection;

/**
 * 协同通知领域消息顶层抽象契约
 * 统一承载目标渠道、@人 目标及核心生命周期校验
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
public abstract class NotificationMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 指定发信的目标渠道名称（为 null 时使用默认渠道）
     */
    private String channel;

    /**
     * @人 目标领域对象，默认初始化为空对象
     */
    private MentionTarget mention = new MentionTarget();

    /**
     * 获取当前消息的载荷类型
     *
     * @return MessageType 消息类型
     */
    public abstract MessageType getMessageType();

    /**
     * 校验当前消息的完整性与合法性，不合法时抛出 NotificationException
     */
    public abstract void validate();

    @SuppressWarnings("unchecked")
    public <T extends NotificationMessage> T channel(String channel) {
        this.channel = channel;
        return (T) this;
    }

    @SuppressWarnings("unchecked")
    public <T extends NotificationMessage> T mention(MentionTarget mention) {
        this.mention = mention != null ? mention : new MentionTarget();
        return (T) this;
    }

    @SuppressWarnings("unchecked")
    public <T extends NotificationMessage> T atAll() {
        if (this.mention == null) {
            this.mention = new MentionTarget();
        }
        this.mention.atAll();
        return (T) this;
    }

    @SuppressWarnings("unchecked")
    public <T extends NotificationMessage> T atAll(boolean isAtAll) {
        if (this.mention == null) {
            this.mention = new MentionTarget();
        }
        this.mention.atAll(isAtAll);
        return (T) this;
    }

    @SuppressWarnings("unchecked")
    public <T extends NotificationMessage> T atMobiles(String... mobiles) {
        if (this.mention == null) {
            this.mention = new MentionTarget();
        }
        this.mention.addMobiles(mobiles);
        return (T) this;
    }

    @SuppressWarnings("unchecked")
    public <T extends NotificationMessage> T atMobiles(Collection<String> mobiles) {
        if (this.mention == null) {
            this.mention = new MentionTarget();
        }
        this.mention.addMobiles(mobiles);
        return (T) this;
    }

    @SuppressWarnings("unchecked")
    public <T extends NotificationMessage> T atUserIds(String... userIds) {
        if (this.mention == null) {
            this.mention = new MentionTarget();
        }
        this.mention.addUserIds(userIds);
        return (T) this;
    }

    @SuppressWarnings("unchecked")
    public <T extends NotificationMessage> T atUserIds(Collection<String> userIds) {
        if (this.mention == null) {
            this.mention = new MentionTarget();
        }
        this.mention.addUserIds(userIds);
        return (T) this;
    }
}
