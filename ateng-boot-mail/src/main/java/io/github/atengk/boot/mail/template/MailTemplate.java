package io.github.atengk.boot.mail.template;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.mail.exception.MailResultCode;
import io.github.atengk.boot.mail.exception.MailSendException;
import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.model.MailAttachment;
import io.github.atengk.boot.mail.model.MailInlineResource;
import io.github.atengk.boot.mail.model.MailMessage;
import io.github.atengk.boot.mail.model.MailSendResult;
import io.github.atengk.boot.mail.model.TrackableMimeMessage;
import io.github.atengk.boot.mail.properties.MailProperties;
import io.github.atengk.boot.mail.registry.MailAccountRegistry;
import io.github.atengk.boot.mail.template.renderer.MailTemplateRenderer;
import io.github.atengk.boot.mail.template.renderer.SimpleMailTemplateRenderer;
import jakarta.mail.internet.InternetAddress;
import lombok.Getter;
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
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 邮件通信核心操作门面
 * <p>
 * 提供高层次命令式与函数式 DSL 发信接口，整合强类型 {@link MailMessage} 领域对象，
 * 支持多账号路由、纯文本、HTML 与动态模板渲染、RFC 6266 大附件流式防爆挂载、正交内联资源 (Inline CID)
 * 以及确定性资源清理与因果异常转译。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
public class MailTemplate {

    private static final Logger log = LoggerFactory.getLogger(MailTemplate.class);

    private final MailAccountRegistry accountRegistry;
    private final MailProperties properties;

    @Getter
    private final MailTemplateRenderer templateRenderer;

    public MailTemplate(MailAccountRegistry accountRegistry, MailProperties properties) {
        this(accountRegistry, properties, new SimpleMailTemplateRenderer());
    }

    public MailTemplate(MailAccountRegistry accountRegistry, MailProperties properties, MailTemplateRenderer templateRenderer) {
        this.accountRegistry = Objects.requireNonNull(accountRegistry, "accountRegistry must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.templateRenderer = templateRenderer != null ? templateRenderer : new SimpleMailTemplateRenderer();
    }

    // ==================== 全能领域对象与函数式 DSL 发信 ====================

    /**
     * 发送邮件（基于强类型领域模型 MailMessage）
     *
     * @param message 邮件领域对象
     * @return 发送结果回执
     */
    public MailSendResult send(MailMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("MailMessage must not be null");
        }
        if (message.getTo() == null || message.getTo().isEmpty()) {
            throw new MailSendException(MailResultCode.MAIL_RECIPIENT_EMPTY, "收件人列表不能为空");
        }

        MailAccount account = accountRegistry.get(message.getAccount());
        if (account == null) {
            throw new MailSendException(MailResultCode.MAIL_ACCOUNT_NOT_FOUND,
                    "未找到发信账号: [" + (message.getAccount() != null ? message.getAccount() : accountRegistry.getDefaultAccountName()) + "]");
        }

        // 容量防爆安全校验
        enforceSizeLimits(message);

        String maskedTo = message.getTo().stream()
                .filter(Objects::nonNull)
                .map(DesensitizeUtil::email)
                .collect(Collectors.joining(","));

        String traceId = resolveTraceId();
        String domain = resolveDomain(account);
        String messageId = "<" + traceId + "-" + System.nanoTime() + "@" + domain + ">";

        log.info("Sending {} email via account [{}] to [{}], subject: [{}], messageId: [{}], attachments: {}, inlines: {}",
                message.isHtml() ? "HTML" : "plaintext", account.getName(), maskedTo, message.getSubject(), messageId,
                message.getAttachments() != null ? message.getAttachments().size() : 0,
                message.getInlines() != null ? message.getInlines().size() : 0);

        try {
            int multipartMode = MimeMessageHelper.MULTIPART_MODE_NO;
            if (message.hasAttachments() && message.hasInlines()) {
                multipartMode = MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED;
            } else if (message.hasAttachments()) {
                multipartMode = MimeMessageHelper.MULTIPART_MODE_MIXED;
            } else if (message.hasInlines()) {
                multipartMode = MimeMessageHelper.MULTIPART_MODE_RELATED;
            }
            TrackableMimeMessage mimeMessage = new TrackableMimeMessage(account.getSender().getSession(), messageId);
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, multipartMode, StandardCharsets.UTF_8.name());

            // 1. 发件人与中文昵称设置
            String fromAddress = (message.getFrom() != null && !message.getFrom().isBlank())
                    ? message.getFrom()
                    : ((account.getDefaultFrom() != null && !account.getDefaultFrom().isBlank())
                    ? account.getDefaultFrom()
                    : account.getUsername());
            String fromName = (message.getFromName() != null && !message.getFromName().isBlank())
                    ? message.getFromName()
                    : account.getDefaultFromName();

            if (fromName != null && !fromName.isBlank()) {
                helper.setFrom(new InternetAddress(fromAddress, fromName, StandardCharsets.UTF_8.name()));
            } else if (fromAddress != null && !fromAddress.isBlank()) {
                helper.setFrom(fromAddress);
            }

            // 2. 收件人、抄送人、密送人与回复地址设置
            helper.setTo(message.getTo().toArray(new String[0]));
            if (message.getCc() != null && !message.getCc().isEmpty()) {
                helper.setCc(message.getCc().toArray(new String[0]));
            }
            if (message.getBcc() != null && !message.getBcc().isEmpty()) {
                helper.setBcc(message.getBcc().toArray(new String[0]));
            }
            if (message.getReplyTo() != null && !message.getReplyTo().isBlank()) {
                helper.setReplyTo(message.getReplyTo());
            }

            // 3. 主题设置
            helper.setSubject(message.getSubject() != null ? message.getSubject() : "");

            // 4. 自定义邮件标头
            if (message.getHeaders() != null) {
                for (Map.Entry<String, String> header : message.getHeaders().entrySet()) {
                    if (header.getKey() != null && header.getValue() != null) {
                        helper.getMimeMessage().setHeader(header.getKey(), header.getValue());
                    }
                }
            }

            // 5. 正文内容装配
            helper.setText(message.getText() != null ? message.getText() : "", message.isHtml());

            // 6. 内联嵌入资源（必须在 helper.setText 之后执行）
            if (message.hasInlines()) {
                for (MailInlineResource inline : message.getInlines()) {
                    helper.addInline(inline.getContentId(), inline.getSource(), inline.getContentType());
                }
            }

            // 7. 附件流式挂载
            if (message.hasAttachments()) {
                for (MailAttachment attachment : message.getAttachments()) {
                    helper.addAttachment(attachment.getFilename(), attachment.getSource(), attachment.getContentType());
                }
            }

            // 8. 物理发送
            long start = System.nanoTime();
            account.getSender().send(mimeMessage);
            Duration duration = Duration.ofNanos(System.nanoTime() - start);

            log.info("Email sent successfully to [{}] in {} ms, messageId: [{}]",
                    maskedTo, duration.toMillis(), messageId);

            return MailSendResult.success(messageId, new ArrayList<>(message.getTo()), duration);
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
        } finally {
            // 确定性资源清理：释放所有流句柄与临时文件
            message.close();
        }
    }

    /**
     * 函数式 DSL 方式发送邮件
     *
     * @param messageConfigurer 邮件消息配置器
     * @return 发送结果回执
     */
    public MailSendResult send(Consumer<MailMessage> messageConfigurer) {
        if (messageConfigurer == null) {
            throw new IllegalArgumentException("messageConfigurer must not be null");
        }
        MailMessage message = new MailMessage();
        messageConfigurer.accept(message);
        return send(message);
    }

    // ==================== 纯文本邮件发送快捷门面 ====================

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
        MailMessage message = new MailMessage()
                .account(accountName)
                .to(to != null ? new ArrayList<>(to) : new ArrayList<>())
                .subject(subject)
                .text(text);
        return send(message);
    }

    // ==================== 富文本 HTML 邮件发送快捷门面 ====================

    /**
     * 发送 HTML 富文本邮件（默认账号）
     *
     * @param to      单个目标收件人
     * @param subject 邮件主题
     * @param html    HTML 正文
     * @return 发送结果回执
     */
    public MailSendResult sendHtml(String to, String subject, String html) {
        return sendHtml(null, to != null ? List.of(to) : List.of(), subject, html);
    }

    /**
     * 发送 HTML 富文本邮件（指定账号）
     *
     * @param accountName 账号逻辑名称
     * @param to          单个目标收件人
     * @param subject     邮件主题
     * @param html        HTML 正文
     * @return 发送结果回执
     */
    public MailSendResult sendHtml(String accountName, String to, String subject, String html) {
        return sendHtml(accountName, to != null ? List.of(to) : List.of(), subject, html);
    }

    /**
     * 发送 HTML 富文本邮件给多个收件人（默认账号）
     *
     * @param to      目标收件人集合
     * @param subject 邮件主题
     * @param html    HTML 正文
     * @return 发送结果回执
     */
    public MailSendResult sendHtml(Collection<String> to, String subject, String html) {
        return sendHtml(null, to, subject, html);
    }

    /**
     * 发送 HTML 富文本邮件给多个收件人（指定账号）
     *
     * @param accountName 账号逻辑名称
     * @param to          目标收件人集合
     * @param subject     邮件主题
     * @param html        HTML 正文
     * @return 发送结果回执
     */
    public MailSendResult sendHtml(String accountName, Collection<String> to, String subject, String html) {
        MailMessage message = new MailMessage()
                .account(accountName)
                .to(to != null ? new ArrayList<>(to) : new ArrayList<>())
                .subject(subject)
                .html(html);
        return send(message);
    }

    // ==================== 模板动态渲染邮件发送快捷门面 ====================

    /**
     * 发送模板渲染邮件（默认账号）
     *
     * @param to           单个目标收件人
     * @param subject      邮件主题
     * @param templateName 模板名称（如 welcome、mail/verify-code 或内联模板字符串）
     * @param model        数据模型字典
     * @return 发送结果回执
     */
    public MailSendResult sendTemplate(String to, String subject, String templateName, Map<String, Object> model) {
        return sendTemplate(null, to != null ? List.of(to) : List.of(), subject, templateName, model);
    }

    /**
     * 发送模板渲染邮件（指定账号）
     *
     * @param accountName  账号逻辑名称
     * @param to           单个目标收件人
     * @param subject      邮件主题
     * @param templateName 模板名称
     * @param model        数据模型字典
     * @return 发送结果回执
     */
    public MailSendResult sendTemplate(String accountName, String to, String subject, String templateName, Map<String, Object> model) {
        return sendTemplate(accountName, to != null ? List.of(to) : List.of(), subject, templateName, model);
    }

    /**
     * 发送模板渲染邮件给多个收件人（默认账号）
     *
     * @param to           目标收件人集合
     * @param subject      邮件主题
     * @param templateName 模板名称
     * @param model        数据模型字典
     * @return 发送结果回执
     */
    public MailSendResult sendTemplate(Collection<String> to, String subject, String templateName, Map<String, Object> model) {
        return sendTemplate(null, to, subject, templateName, model);
    }

    /**
     * 发送模板渲染邮件给多个收件人（指定账号）
     *
     * @param accountName  账号逻辑名称
     * @param to           目标收件人集合
     * @param subject      邮件主题
     * @param templateName 模板名称
     * @param model        数据模型字典
     * @return 发送结果回执
     */
    public MailSendResult sendTemplate(String accountName, Collection<String> to, String subject, String templateName, Map<String, Object> model) {
        String renderedHtml = templateRenderer.render(templateName, model);
        return sendHtml(accountName, to, subject, renderedHtml);
    }

    // ==================== 内部防御性校验与辅助逻辑 ====================

    /**
     * 校验邮件附件与总体积是否超出阈值限制，实施 Fail-Fast 防爆熔断
     *
     * @param message 待发送邮件消息
     */
    private void enforceSizeLimits(MailMessage message) {
        long maxAttachmentBytes = (properties.getMaxAttachmentSize() != null)
                ? properties.getMaxAttachmentSize().toBytes()
                : Long.MAX_VALUE;
        long maxTotalBytes = (properties.getMaxTotalSize() != null)
                ? properties.getMaxTotalSize().toBytes()
                : Long.MAX_VALUE;

        // 1. 单附件大小检查
        if (message.hasAttachments()) {
            for (MailAttachment attachment : message.getAttachments()) {
                if (attachment != null && attachment.getSize() != null && attachment.getSize() > maxAttachmentBytes) {
                    throw new MailSendException(MailResultCode.MAIL_ATTACHMENT_SIZE_EXCEEDED,
                            "邮件附件 [" + attachment.getFilename() + "] 大小 (" + attachment.getSize()
                                    + " 字节) 超出单个附件上限 (" + properties.getMaxAttachmentSize() + ")");
                }
            }
        }

        // 2. 邮件已知总容量检查
        long estimatedTotal = (message.getText() != null)
                ? message.getText().getBytes(StandardCharsets.UTF_8).length
                : 0L;

        if (message.hasAttachments()) {
            for (MailAttachment attachment : message.getAttachments()) {
                if (attachment != null && attachment.getSize() != null) {
                    estimatedTotal += attachment.getSize();
                }
            }
        }

        if (message.hasInlines()) {
            for (MailInlineResource inline : message.getInlines()) {
                if (inline != null && inline.getSize() != null) {
                    estimatedTotal += inline.getSize();
                }
            }
        }

        if (estimatedTotal > maxTotalBytes) {
            throw new MailSendException(MailResultCode.MAIL_TOTAL_SIZE_EXCEEDED,
                    "邮件总容量预估 (" + estimatedTotal + " 字节) 超出总体积上限 (" + properties.getMaxTotalSize() + ")");
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
