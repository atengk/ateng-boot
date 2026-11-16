package io.github.atengk.boot.mail.model;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import lombok.Getter;
import org.eclipse.angus.mail.smtp.SMTPMessage;

/**
 * 支持自定义结构化 Message-ID 且支持单封粒度 Envelope MAIL FROM 隔离的 SMTPMessage 子类
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Getter
public class TrackableMimeMessage extends SMTPMessage {

    /**
     * 自定义全局唯一 Message-ID
     */
    private final String customMessageId;

    public TrackableMimeMessage(Session session, String customMessageId) {
        super(session);
        this.customMessageId = customMessageId;
    }

    @Override
    protected void updateMessageID() throws MessagingException {
        if (customMessageId != null && !customMessageId.isBlank()) {
            setHeader("Message-ID", customMessageId);
        } else {
            super.updateMessageID();
        }
    }
}
