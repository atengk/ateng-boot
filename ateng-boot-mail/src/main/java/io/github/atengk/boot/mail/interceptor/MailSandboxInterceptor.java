package io.github.atengk.boot.mail.interceptor;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.mail.exception.MailResultCode;
import io.github.atengk.boot.mail.exception.MailSendException;
import io.github.atengk.boot.mail.model.MailMessage;
import io.github.atengk.boot.mail.properties.MailSandboxProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.util.PatternMatchUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 邮件测试安全沙箱发信拦截器
 * <p>
 * 提供非生产环境邮件安全治理防卫体系，设定为框架最高优先级 ({@link Ordered#HIGHEST_PRECEDENCE})，
 * 确保在所有业务拦截器之前生效。支持白名单精确/通配符放行、强制重定向清场与纯日志模拟发信。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Slf4j
public class MailSandboxInterceptor implements MailSendInterceptor, Ordered {

    /**
     * 沙箱纯日志模拟发信标记标头
     */
    public static final String HEADER_SANDBOX_LOG_ONLY = "X-Mail-Sandbox-Log-Only";

    /**
     * 沙箱重定向原始收件人审计记录标头
     */
    public static final String HEADER_SANDBOX_REDIRECTED_FROM = "X-Sandbox-Redirected-From";

    private final MailSandboxProperties properties;

    public MailSandboxInterceptor(MailSandboxProperties properties) {
        this.properties = properties != null ? properties : new MailSandboxProperties();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public boolean preSend(MailMessage message) {
        if (!properties.isEnabled() || message == null) {
            return true;
        }

        // 1. 纯日志模拟发信模式 (log-only=true)：不发起真实网络连接，仅在控制台输出脱敏完整报文
        if (properties.isLogOnly()) {
            logDesensitizedMessage(message);
            Map<String, String> headers = message.getHeaders() != null
                    ? new LinkedHashMap<>(message.getHeaders())
                    : new LinkedHashMap<>();
            headers.put(HEADER_SANDBOX_LOG_ONLY, "true");
            message.setHeaders(headers);
            return true;
        }

        List<String> toList = message.getTo() != null ? message.getTo() : Collections.emptyList();
        List<String> ccList = message.getCc() != null ? message.getCc() : Collections.emptyList();
        List<String> bccList = message.getBcc() != null ? message.getBcc() : Collections.emptyList();

        // 2. 白名单模式校验：检查 To/Cc/Bcc 全部收件人是否 100% 匹配 allowed-recipients 规则
        boolean hasAnyRecipient = !toList.isEmpty() || !ccList.isEmpty() || !bccList.isEmpty();
        boolean allAllowed = hasAnyRecipient
                && toList.stream().allMatch(this::isRecipientAllowed)
                && ccList.stream().allMatch(this::isRecipientAllowed)
                && bccList.stream().allMatch(this::isRecipientAllowed);

        if (allAllowed) {
            log.debug("[MAIL-SANDBOX] All recipients matched allowed-recipients whitelist. Physical delivery allowed for Subject: [{}]",
                    message.getSubject());
            return true;
        }

        // 3. 重定向模式：包含非白名单收件人时，将 To 重定向至测试邮箱，并彻底清空 Cc/Bcc 杜绝外部泄密
        String redirectTo = properties.getRedirectTo();
        if (redirectTo == null || redirectTo.isBlank()) {
            log.error("[MAIL-SANDBOX] Mail blocked: non-whitelisted recipients detected but ateng.boot.mail.sandbox.redirect-to is not configured. To: {}, Cc: {}, Bcc: {}",
                    maskRecipients(toList), maskRecipients(ccList), maskRecipients(bccList));
            throw new MailSendException(MailResultCode.MAIL_SEND_ERROR,
                    "邮件测试沙箱已拦截非白名单收件人，但未配置 ateng.boot.mail.sandbox.redirect-to 重定向邮箱地址");
        }

        String originalTo = !toList.isEmpty() ? String.join(",", toList) : "none";
        log.warn("[MAIL-SANDBOX-REDIRECT] Non-whitelisted mail detected. Original To: [{}], Cc: [{}], Bcc: [{}] -> Redirecting to: [{}], clearing Cc/Bcc",
                maskRecipients(toList), maskRecipients(ccList), maskRecipients(bccList), redirectTo.trim());

        // 强制重写目标 To 列表
        message.setTo(new ArrayList<>(List.of(redirectTo.trim())));

        // 强制清空 Cc 和 Bcc 列表（防御调用方传入不可变集合，直接置换为新可变实例）
        message.setCc(new ArrayList<>());
        message.setBcc(new ArrayList<>());

        // 主题追加 [SANDBOX-REDIRECT to: original@...] 标头供测试核验
        String prefix = "[SANDBOX-REDIRECT to: " + originalTo + "] ";
        String originalSubject = message.getSubject() != null ? message.getSubject() : "";
        message.setSubject(prefix + originalSubject);

        // 注入审计追踪标头（防御不可变 Map）
        Map<String, String> headers = message.getHeaders() != null
                ? new LinkedHashMap<>(message.getHeaders())
                : new LinkedHashMap<>();
        headers.put(HEADER_SANDBOX_REDIRECTED_FROM, originalTo);
        message.setHeaders(headers);

        return true;
    }

    /**
     * 校验指定收件人邮箱是否符合白名单匹配规则（支持精确匹配与通配符匹配）
     *
     * @param recipient 待校验的收件人邮箱（支持包含友好昵称格式，如 "Tester <test@internal.com>"）
     * @return 返回 true 表示匹配白名单允许放行，false 表示不匹配
     */
    public boolean isRecipientAllowed(String recipient) {
        if (recipient == null || recipient.isBlank()) {
            return false;
        }
        List<String> allowed = properties.getAllowedRecipients();
        if (allowed == null || allowed.isEmpty()) {
            return false;
        }
        String cleanEmail = extractCleanEmail(recipient);
        if (cleanEmail.isEmpty()) {
            return false;
        }
        String lowerEmail = cleanEmail.toLowerCase(Locale.ROOT);
        for (String pattern : allowed) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            String lowerPattern = pattern.trim().toLowerCase(Locale.ROOT);
            if (PatternMatchUtils.simpleMatch(lowerPattern, lowerEmail)) {
                return true;
            }
        }
        return false;
    }

    private String extractCleanEmail(String address) {
        if (address == null) {
            return "";
        }
        int start = address.indexOf('<');
        int end = address.indexOf('>');
        if (start >= 0 && end > start) {
            return address.substring(start + 1, end).trim();
        }
        return address.trim();
    }

    private void logDesensitizedMessage(MailMessage message) {
        String desensitizedTo = maskRecipients(message.getTo());
        String desensitizedCc = maskRecipients(message.getCc());
        String desensitizedBcc = maskRecipients(message.getBcc());
        int attachmentCount = message.getAttachments() != null ? message.getAttachments().size() : 0;
        int inlineCount = message.getInlines() != null ? message.getInlines().size() : 0;
        log.info("[MAIL-SANDBOX-LOG-ONLY] Simulated email dispatch: To=[{}], Cc=[{}], Bcc=[{}], Subject=[{}], BodyLength={}, HTML={}, Attachments={}, Inlines={}, Headers={}",
                desensitizedTo, desensitizedCc, desensitizedBcc, message.getSubject(),
                message.getText() != null ? message.getText().length() : 0,
                message.isHtml(), attachmentCount, inlineCount, message.getHeaders());
    }

    private String maskRecipients(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        return list.stream()
                .filter(Objects::nonNull)
                .map(DesensitizeUtil::email)
                .collect(Collectors.joining(","));
    }
}
