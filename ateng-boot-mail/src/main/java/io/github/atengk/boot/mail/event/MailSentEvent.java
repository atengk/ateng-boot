package io.github.atengk.boot.mail.event;

import io.github.atengk.boot.mail.model.MailMessage;
import io.github.atengk.boot.mail.model.MailSendResult;
import lombok.Getter;

import java.util.Objects;

/**
 * 邮件物理发信成功生命周期事件
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Getter
public class MailSentEvent extends MailSendEvent {

    /**
     * 发信成功回执载荷
     */
    private final MailSendResult result;

    public MailSentEvent(Object source, MailMessage message, MailSendResult result) {
        super(source, message);
        this.result = Objects.requireNonNull(result, "result must not be null");
    }
}
