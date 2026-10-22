package io.github.atengk.boot.mail.event;

import io.github.atengk.boot.mail.model.MailMessage;
import lombok.Getter;

import java.util.Objects;

/**
 * 邮件物理发信失败生命周期事件（包含原始因果链与错误详情）
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Getter
public class MailSendFailedEvent extends MailSendEvent {

    /**
     * 发信失败捕获的原始异常
     */
    private final Throwable cause;

    public MailSendFailedEvent(Object source, MailMessage message, Throwable cause) {
        super(source, message);
        this.cause = Objects.requireNonNull(cause, "cause must not be null");
    }
}
