package io.github.atengk.boot.sample.mail.listener;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.mail.event.MailQuotaExceededEvent;
import io.github.atengk.boot.mail.event.MailSendFailedEvent;
import io.github.atengk.boot.mail.event.MailSendingEvent;
import io.github.atengk.boot.mail.event.MailSentEvent;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 示例邮件全生命周期事件监听器
 * <p>
 * 演示监听框架在发信前 ({@link MailSendingEvent})、发信成功 ({@link MailSentEvent})、
 * 发信失败 ({@link MailSendFailedEvent}) 与日配额熔断 ({@link MailQuotaExceededEvent}) 广播的原生领域事件，
 * 便于生产对接审计与监控告警。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Component
public class SampleMailEventListener {

    private static final Logger log = LoggerFactory.getLogger(SampleMailEventListener.class);

    @Getter
    private final AtomicInteger sendingCount = new AtomicInteger(0);

    @Getter
    private final AtomicInteger sentCount = new AtomicInteger(0);

    @Getter
    private final AtomicInteger failedCount = new AtomicInteger(0);

    @Getter
    private final AtomicInteger quotaExceededCount = new AtomicInteger(0);

    @Getter
    private final AtomicReference<MailQuotaExceededEvent> lastQuotaExceededEvent = new AtomicReference<>();

    @EventListener
    public void onMailSending(MailSendingEvent event) {
        sendingCount.incrementAndGet();
        log.info("[Event] Mail is sending, subject: [{}], recipients: [{}]",
                event.getMessage().getSubject(), maskRecipients(event.getMessage().getTo()));
    }

    @EventListener
    public void onMailSent(MailSentEvent event) {
        sentCount.incrementAndGet();
        log.info("[Event] Mail sent successfully, messageId: [{}], duration: {} ms",
                event.getResult().getMessageId(), event.getResult().getDuration().toMillis());
    }

    @EventListener
    public void onMailSendFailed(MailSendFailedEvent event) {
        failedCount.incrementAndGet();
        log.warn("[Event] Mail sending failed, subject: [{}], reason: {}",
                event.getMessage().getSubject(), event.getCause().getMessage());
    }

    @EventListener
    public void onMailQuotaExceeded(MailQuotaExceededEvent event) {
        quotaExceededCount.incrementAndGet();
        lastQuotaExceededEvent.set(event);
        log.warn("[Event] Mail daily quota exceeded, account: [{}], date: {}, limit: {}, current: {}, requested: {}",
                event.getAccountName(), event.getQuotaDate(), event.getDailyLimit(),
                event.getCurrentCount(), event.getRequestedCount());
    }

    /**
     * 重置事件计数器（用于单测隔离）
     */
    public void reset() {
        sendingCount.set(0);
        sentCount.set(0);
        failedCount.set(0);
        quotaExceededCount.set(0);
        lastQuotaExceededEvent.set(null);
    }

    private static String maskRecipients(List<String> recipients) {
        if (recipients == null || recipients.isEmpty()) {
            return "";
        }
        return recipients.stream()
                .filter(Objects::nonNull)
                .map(DesensitizeUtil::email)
                .collect(Collectors.joining(","));
    }
}
