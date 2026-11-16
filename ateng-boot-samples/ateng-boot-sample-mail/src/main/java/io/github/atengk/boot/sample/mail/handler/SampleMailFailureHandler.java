package io.github.atengk.boot.sample.mail.handler;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.mail.handler.MailSendFailureHandler;
import io.github.atengk.boot.mail.model.MailMessage;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 发信终态失败补偿处理器样例实现
 * <p>
 * 演示通过实现 {@link MailSendFailureHandler} 扩展点，在发信终态失败时执行告警记录或业务兜底。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Component
@Getter
public class SampleMailFailureHandler implements MailSendFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(SampleMailFailureHandler.class);

    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicReference<Throwable> lastCause = new AtomicReference<>();
    private final AtomicReference<MailMessage> lastFailedMessage = new AtomicReference<>();

    @Override
    public void onFailure(MailMessage message, Throwable cause) {
        failureCount.incrementAndGet();
        lastCause.set(cause);
        lastFailedMessage.set(message);
        log.warn("[SampleSafetyNet] Mail sending failed for subject [{}], recipients: [{}], cause: {}",
                message != null ? message.getSubject() : null,
                maskRecipients(message != null ? message.getTo() : Collections.emptyList()),
                cause != null ? cause.getMessage() : null);
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

    public void reset() {
        failureCount.set(0);
        lastCause.set(null);
        lastFailedMessage.set(null);
    }
}
