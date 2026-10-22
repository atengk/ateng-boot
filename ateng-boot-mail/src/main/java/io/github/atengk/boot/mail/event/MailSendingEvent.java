package io.github.atengk.boot.mail.event;

import io.github.atengk.boot.mail.model.MailMessage;

/**
 * 邮件物理发信前生命周期事件
 *
 * @author Ateng
 * @since 2026-10-22
 */
public class MailSendingEvent extends MailSendEvent {

    public MailSendingEvent(Object source, MailMessage message) {
        super(source, message);
    }
}
