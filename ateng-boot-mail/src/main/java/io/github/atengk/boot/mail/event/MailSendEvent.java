package io.github.atengk.boot.mail.event;

import io.github.atengk.boot.mail.model.MailMessage;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.Objects;

/**
 * 邮件通信生命周期事件抽象基类
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Getter
public abstract class MailSendEvent extends ApplicationEvent {

    /**
     * 邮件领域消息模型
     */
    private final MailMessage message;

    public MailSendEvent(Object source, MailMessage message) {
        super(source);
        this.message = Objects.requireNonNull(message, "message must not be null");
    }
}
