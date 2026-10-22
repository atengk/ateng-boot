package io.github.atengk.boot.mail.template;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.mail.exception.MailResultCode;
import io.github.atengk.boot.mail.exception.MailSendException;
import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.model.MailSendResult;
import io.github.atengk.boot.mail.model.TrackableMimeMessage;
import io.github.atengk.boot.mail.properties.MailProperties;
import io.github.atengk.boot.mail.registry.MailAccountRegistry;
import jakarta.mail.internet.InternetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 邮件通信核心操作门面
 *
 * @author Ateng
 * @since 2026-10-22
 */
public class MailTemplate {

    private static final Logger log = LoggerFactory.getLogger(MailTemplate.class);

    private final MailAccountRegistry accountRegistry;
    private final MailProperties properties;

    public MailTemplate(MailAccountRegistry accountRegistry, MailProperties properties) {
        this.accountRegistry = Objects.requireNonNull(accountRegistry, "accountRegistry must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * 发送纯文本邮件（默认账号）
     *
     * @param to      单个目标收件人
     * @param subject 邮件主题
     * @param text    纯文本正文
     * @return 发送结果回执
     */
    public MailSendResult sendText(String to, String subject, String text) {
        return sendText(null, to != null ? List.of(to) : List.of(), subject, text);
    }

    /**
     * 发送纯文本邮件（指定账号）
     *
     * @param accountName 账号逻辑名称
     * @param to          单个目标收件人
     * @param subject     邮件主题
     * @param text        纯文本正文
     * @return 发送结果回执
     */
    public MailSendResult sendText(String accountName, String to, String subject, String text) {
        return sendText(accountName, to != null ? List.of(to) : List.of(), subject, text);
    }

    /**
     * 发送纯文本邮件给多个收件人（默认账号）
     *
     * @param to      目标收件人集合
     * @param subject 邮件主题
     * @param text    纯文本正文
     * @return 发送结果回执
     */
    public MailSendResult sendText(Collection<String> to, String subject, String text) {
        return sendText(null, to, subject, text);
    }

    /**
     * 发送纯文本邮件给多个收件人（指定账号）
     *
     * @param accountName 账号逻辑名称
     * @param to          目标收件人集合
     * @param subject     邮件主题
     * @param text        纯文本正文
     * @return 发送结果回执
     */
    public MailSendResult sendText(String accountName, Collection<String> to, String subject, String text) {
        if (to == null || to.isEmpty()) {
            throw new MailSendException(MailResultCode.MAIL_RECIPIENT_EMPTY, "收件人列表不能为空");
        }

        MailAccount account = accountRegistry.get(accountName);
        if (account == null) {
            throw new MailSendException(MailResultCode.MAIL_ACCOUNT_NOT_FOUND,
                    "未找到发信账号: [" + (accountName != null ? accountName : accountRegistry.getDefaultAccountName()) + "]");
        }

        String maskedTo = to.stream()
                .filter(Objects::nonNull)
                .map(DesensitizeUtil::email)
                .collect(Collectors.joining(","));

        String traceId = resolveTraceId();
        String domain = resolveDomain(account);
        String messageId = "<" + traceId + "-" + System.nanoTime() + "@" + domain + ">";

        log.info("Sending plaintext email via account [{}] to [{}], subject: [{}], messageId: [{}]",
                account.getName(), maskedTo, subject, messageId);

        try {
            TrackableMimeMessage mimeMessage = new TrackableMimeMessage(account.getSender().getSession(), messageId);
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, false, StandardCharsets.UTF_8.name());

            String fromAddress = (account.getDefaultFrom() != null && !account.getDefaultFrom().isBlank())
                    ? account.getDefaultFrom()
                    : account.getUsername();

            if (account.getDefaultFromName() != null && !account.getDefaultFromName().isBlank()) {
                helper.setFrom(new InternetAddress(fromAddress, account.getDefaultFromName(), StandardCharsets.UTF_8.name()));
            } else if (fromAddress != null && !fromAddress.isBlank()) {
                helper.setFrom(fromAddress);
            }

            helper.setTo(to.toArray(new String[0]));
            helper.setSubject(subject != null ? subject : "");
            helper.setText(text != null ? text : "", false);

            long start = System.nanoTime();
            account.getSender().send(mimeMessage);
            Duration duration = Duration.ofNanos(System.nanoTime() - start);

            log.info("Email sent successfully to [{}] in {} ms, messageId: [{}]",
                    maskedTo, duration.toMillis(), messageId);

            return MailSendResult.success(messageId, new ArrayList<>(to), duration);
        } catch (MailAuthenticationException e) {
            log.error("Mail authentication failed for account [{}], to: [{}]", account.getName(), maskedTo, e);
            throw new MailSendException(MailResultCode.MAIL_AUTHENTICATION_ERROR, "邮件服务器身份认证失败: " + e.getMessage(), e);
        } catch (org.springframework.mail.MailSendException e) {
            log.error("Failed to deliver email to [{}]: {}", maskedTo, e.getMessage(), e);
            if (isTimeoutException(e)) {
                throw new MailSendException(MailResultCode.MAIL_CONNECT_TIMEOUT, "邮件服务器连接或读写超时: " + e.getMessage(), e);
            }
            throw new MailSendException(MailResultCode.MAIL_SEND_ERROR, "邮件发送失败: " + e.getMessage(), e);
        } catch (MailSendException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error while sending email to [{}]: {}", maskedTo, e.getMessage(), e);
            throw new MailSendException(MailResultCode.MAIL_SEND_ERROR, "邮件发送异常: " + e.getMessage(), e);
        }
    }

    private String resolveTraceId() {
        String traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            return traceId;
        }
        return IdUtil.fastSimpleUUID();
    }

    private String resolveDomain(MailAccount account) {
        if (account.getUsername() != null && account.getUsername().contains("@")) {
            return account.getUsername().substring(account.getUsername().indexOf('@') + 1);
        }
        if (account.getHost() != null && !account.getHost().isBlank()) {
            return account.getHost();
        }
        return "ateng-boot.local";
    }

    private boolean isTimeoutException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && (message.toLowerCase().contains("timeout") || message.toLowerCase().contains("timed out"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
