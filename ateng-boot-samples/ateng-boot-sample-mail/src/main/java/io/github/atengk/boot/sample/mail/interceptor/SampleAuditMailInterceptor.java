package io.github.atengk.boot.sample.mail.interceptor;

import io.github.atengk.boot.mail.interceptor.MailSendInterceptor;
import io.github.atengk.boot.mail.model.MailMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * 示例发信审计与安全合规拦截器
 * <p>
 * 演示通过 {@link MailSendInterceptor} SPI 拦截非法收件人或向邮件动态注入合规安全标头。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Component
public class SampleAuditMailInterceptor implements MailSendInterceptor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(SampleAuditMailInterceptor.class);

    public static final String BLOCKED_EMAIL = "blocked@example.com";
    public static final String AUDIT_HEADER_KEY = "X-Audit-Trace";
    public static final String AUDIT_HEADER_VALUE = "audited-by-sample-interceptor";

    @Override
    public boolean preSend(MailMessage message) {
        if (message == null || message.getTo() == null) {
            return true;
        }

        // 1. 安全黑名单阻断拦截
        if (message.getTo().contains(BLOCKED_EMAIL)) {
            log.warn("Blocked sending email to blacklisted address: [{}]", BLOCKED_EMAIL);
            return false;
        }

        // 2. 动态注入全链路审计与合规跟踪标头
        message.header(AUDIT_HEADER_KEY, AUDIT_HEADER_VALUE);
        log.debug("Injected audit header [{}] to mail: [{}]", AUDIT_HEADER_KEY, message.getSubject());
        return true;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
