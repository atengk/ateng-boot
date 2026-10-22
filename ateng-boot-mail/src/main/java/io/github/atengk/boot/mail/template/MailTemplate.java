package io.github.atengk.boot.mail.template;

import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.ContextCopyingTaskDecorator;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.RetryUtil;
import io.github.atengk.boot.mail.event.MailSendEvent;
import io.github.atengk.boot.mail.event.MailSendFailedEvent;
import io.github.atengk.boot.mail.event.MailSendingEvent;
import io.github.atengk.boot.mail.event.MailSentEvent;
import io.github.atengk.boot.mail.exception.MailResultCode;
import io.github.atengk.boot.mail.exception.MailSendException;
import io.github.atengk.boot.mail.handler.MailSendFailureHandler;
import io.github.atengk.boot.mail.interceptor.MailSendInterceptor;
import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.model.MailAttachment;
import io.github.atengk.boot.mail.model.MailInlineResource;
import io.github.atengk.boot.mail.model.MailMessage;
import io.github.atengk.boot.mail.model.MailSendResult;
import io.github.atengk.boot.mail.model.TrackableMimeMessage;
import io.github.atengk.boot.mail.properties.MailProperties;
import io.github.atengk.boot.mail.properties.MailRetryProperties;
import io.github.atengk.boot.mail.registry.MailAccountRegistry;
import io.github.atengk.boot.mail.resolver.MailAccountResolver;
import io.github.atengk.boot.mail.resolver.TenantMailAccountResolver;
import io.github.atengk.boot.mail.template.renderer.MailTemplateRenderer;
import io.github.atengk.boot.mail.template.renderer.SimpleMailTemplateRenderer;
import jakarta.mail.internet.InternetAddress;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.io.InputStreamSource;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.ClassUtils;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 邮件通信核心操作门面
 * <p>
 * 提供高层次命令式与函数式 DSL 发信接口，整合强类型 {@link MailMessage} 领域对象，
 * 支持多账号路由、受管异步发信与四维上下文透传、本地事务感知发信（{@code sendAfterCommit}）、
 * 纯文本、HTML 与动态模板渲染、RFC 6266 大附件流式防爆挂载、正交内联资源 (Inline CID)、
 * 弹性指数退避重试（{@link RetryUtil}）、三重失败补偿安全网（日志/事件/SPI）以及确定性资源清理。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
public class MailTemplate implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MailTemplate.class);

    private static final boolean SPRING_TX_PRESENT = ClassUtils.isPresent(
            "org.springframework.transaction.support.TransactionSynchronizationManager",
            MailTemplate.class.getClassLoader()
    );

    private final MailAccountRegistry accountRegistry;
    private final MailProperties properties;

    @Getter
    private final MailTemplateRenderer templateRenderer;

    @Getter
    private final MailAccountResolver accountResolver;

    @Getter
    private final Executor executor;

    private final boolean defaultExecutorCreated;

    @Getter
    private final ApplicationEventPublisher eventPublisher;

    @Getter
    private final List<MailSendFailureHandler> failureHandlers;

    @Getter
    private final List<MailSendInterceptor> interceptors;

    public MailTemplate(MailAccountRegistry accountRegistry, MailProperties properties) {
        this(accountRegistry, properties, new SimpleMailTemplateRenderer(), new TenantMailAccountResolver(), null, null, null, null);
    }

    public MailTemplate(MailAccountRegistry accountRegistry, MailProperties properties, MailTemplateRenderer templateRenderer) {
        this(accountRegistry, properties, templateRenderer, new TenantMailAccountResolver(), null, null, null, null);
    }

    public MailTemplate(MailAccountRegistry accountRegistry, MailProperties properties, MailTemplateRenderer templateRenderer, MailAccountResolver accountResolver) {
        this(accountRegistry, properties, templateRenderer, accountResolver, null, null, null, null);
    }

    public MailTemplate(MailAccountRegistry accountRegistry, MailProperties properties, MailTemplateRenderer templateRenderer, MailAccountResolver accountResolver, Executor executor) {
        this(accountRegistry, properties, templateRenderer, accountResolver, executor, null, null, null);
    }

    public MailTemplate(MailAccountRegistry accountRegistry, MailProperties properties, MailTemplateRenderer templateRenderer, MailAccountResolver accountResolver, Executor executor, ApplicationEventPublisher eventPublisher, List<MailSendFailureHandler> failureHandlers) {
        this(accountRegistry, properties, templateRenderer, accountResolver, executor, eventPublisher, failureHandlers, null);
    }

    public MailTemplate(MailAccountRegistry accountRegistry, MailProperties properties, MailTemplateRenderer templateRenderer, MailAccountResolver accountResolver, Executor executor, ApplicationEventPublisher eventPublisher, List<MailSendFailureHandler> failureHandlers, List<MailSendInterceptor> interceptors) {
        this.accountRegistry = Objects.requireNonNull(accountRegistry, "accountRegistry must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.templateRenderer = templateRenderer != null ? templateRenderer : new SimpleMailTemplateRenderer();
        this.accountResolver = accountResolver != null ? accountResolver : new TenantMailAccountResolver();
        if (executor != null) {
            this.executor = executor;
            this.defaultExecutorCreated = false;
        } else {
            this.executor = createDefaultExecutor();
            this.defaultExecutorCreated = true;
        }
        this.eventPublisher = eventPublisher;
        this.failureHandlers = failureHandlers != null ? Collections.unmodifiableList(new ArrayList<>(failureHandlers)) : Collections.emptyList();
        if (interceptors != null) {
            List<MailSendInterceptor> sortedInterceptors = new ArrayList<>(interceptors);
            AnnotationAwareOrderComparator.sort(sortedInterceptors);
            this.interceptors = Collections.unmodifiableList(sortedInterceptors);
        } else {
            this.interceptors = Collections.emptyList();
        }
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

        MailAccount account;
        String explicitAccountName = message.getAccount();
        if (explicitAccountName != null && !explicitAccountName.isBlank()) {
            // 显式指定账号：找不到则直接抛出受控异常 Fail-Fast
            account = accountRegistry.get(explicitAccountName);
            if (account == null) {
                throw new MailSendException(MailResultCode.MAIL_ACCOUNT_NOT_FOUND,
                        "未找到指定的发信账号: [" + explicitAccountName + "]");
            }
        } else {
            // 未显式指定账号：触发动态路由解析器（如多租户上下文）
            String resolvedName = accountResolver.resolveAccount(message);
            if (resolvedName != null && !resolvedName.isBlank()) {
                account = accountRegistry.get(resolvedName);
            } else {
                account = null;
            }
            // 未命中对应租户账号时，平滑安全回退至全局默认发信账号
            if (account == null) {
                account = accountRegistry.getDefault();
            }
        }

        return sendWithAccount(account, message);
    }

    /**
     * 指定发信账号物理发送邮件
     *
     * @param account 发信账号（需已包含物理 Sender）
     * @param message 邮件消息模型
     * @return 发送结果回执
     */
    public MailSendResult sendWithAccount(MailAccount account, MailMessage message) {
        if (account == null) {
            throw new IllegalArgumentException("MailAccount must not be null");
        }
        if (message == null) {
            throw new IllegalArgumentException("MailMessage must not be null");
        }
        if (message.getTo() == null || message.getTo().isEmpty()) {
            throw new MailSendException(MailResultCode.MAIL_RECIPIENT_EMPTY, "收件人列表不能为空");
        }

        String maskedTo = (message.getTo() != null)
                ? message.getTo().stream().filter(Objects::nonNull).map(DesensitizeUtil::email).collect(Collectors.joining(","))
                : "";

        try {
            // 容量防爆安全校验
            enforceSizeLimits(message);

            // 发信拦截器链前置执行
            applyInterceptors(message);

            String traceId = resolveTraceId();
            String domain = resolveDomain(account);
            String messageId = "<" + traceId + "-" + System.nanoTime() + "@" + domain + ">";

            publishEvent(new MailSendingEvent(this, message));

            MailSendResult result;
            MailRetryProperties retryProps = properties.getRetry();
            if (retryProps != null && retryProps.isEnabled()) {
                RetryUtil.RetryConfig retryConfig = RetryUtil.RetryConfig.builder()
                        .maxAttempts(retryProps.getMaxAttempts())
                        .delay(retryProps.getDelay())
                        .multiplier(retryProps.getMultiplier())
                        .maxDelay(retryProps.getMaxDelay())
                        .jitter(retryProps.isJitter())
                        .retryPredicate(this::isRetryableException)
                        .onRetry((attempt, error) -> log.warn("Sending email to [{}] failed on attempt {}, preparing retry. Reason: {}",
                                maskedTo, attempt, error.getMessage()))
                        .build();
                result = RetryUtil.execute(() -> doSendPhysical(account, message, maskedTo, traceId, messageId), retryConfig);
            } else {
                result = doSendPhysical(account, message, maskedTo, traceId, messageId);
            }

            publishEvent(new MailSentEvent(this, message, result));
            return result;
        } catch (Throwable t) {
            triggerFailureSafetyNet(message, maskedTo, t);
            if (t instanceof MailSendException mse) {
                throw mse;
            }
            throw new MailSendException(MailResultCode.MAIL_SEND_ERROR, "邮件发送失败: " + t.getMessage(), t);
        } finally {
            // 确定性资源清理：释放所有流句柄与临时文件
            message.close();
        }
    }

    private MailSendResult doSendPhysical(MailAccount account, MailMessage message, String maskedTo, String traceId, String messageId) {
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
            AtomicReference<MailSendException> sizeLimitException = new AtomicReference<>();
            if (message.hasAttachments()) {
                long maxAttachmentBytes = (properties.getMaxAttachmentSize() != null)
                        ? properties.getMaxAttachmentSize().toBytes()
                        : Long.MAX_VALUE;
                for (MailAttachment attachment : message.getAttachments()) {
                    InputStreamSource source = attachment.getSource();
                    // 若附件容量预先未知，挂载流式计数防爆数据源，严防无界裸流耗尽物理内存
                    if (attachment.getSize() == null && maxAttachmentBytes < Long.MAX_VALUE && source != null) {
                        source = wrapBoundedSource(attachment.getFilename(), source, maxAttachmentBytes, sizeLimitException);
                    }
                    helper.addAttachment(attachment.getFilename(), source, attachment.getContentType());
                }
            }

            // 8. 物理发送
            long start = System.nanoTime();
            account.getSender().send(mimeMessage);

            // 防御性熔断：若流式读取组装过程中检测到容量超限（包括被底层 MIME 编码探测吞咽的异常），立即阻断并抛出
            if (sizeLimitException.get() != null) {
                throw sizeLimitException.get();
            }

            Duration duration = Duration.ofNanos(System.nanoTime() - start);

            log.info("Email sent successfully to [{}] in {} ms, messageId: [{}]",
                    maskedTo, duration.toMillis(), messageId);

            return MailSendResult.success(messageId, new ArrayList<>(message.getTo()), duration);
        } catch (MailAuthenticationException e) {
            throw new MailSendException(MailResultCode.MAIL_AUTHENTICATION_ERROR, "邮件服务器身份认证失败: " + e.getMessage(), e);
        } catch (org.springframework.mail.MailSendException e) {
            MailSendException nestedMse = findNestedMailSendException(e);
            if (nestedMse != null) {
                throw nestedMse;
            }
            if (isTimeoutException(e)) {
                throw new MailSendException(MailResultCode.MAIL_CONNECT_TIMEOUT, "邮件服务器连接或读写超时: " + e.getMessage(), e);
            }
            throw new MailSendException(MailResultCode.MAIL_SEND_ERROR, "邮件发送失败: " + e.getMessage(), e);
        } catch (MailSendException e) {
            throw e;
        } catch (Exception e) {
            MailSendException nestedMse = findNestedMailSendException(e);
            if (nestedMse != null) {
                throw nestedMse;
            }
            throw new MailSendException(MailResultCode.MAIL_SEND_ERROR, "邮件发送异常: " + e.getMessage(), e);
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

    /**
     * 基于编程式临时账号发起邮件发送会话（底层复用受控容量的 LRU 物理 Sender，防范内存泄漏与重复连接）
     *
     * @param account 动态发信账号模型
     * @return 动态账号发信会话门面
     */
    public DynamicMailSession forDynamicAccount(MailAccount account) {
        if (account == null) {
            throw new IllegalArgumentException("Dynamic MailAccount must not be null");
        }
        MailAccount managedAccount = accountRegistry.getOrCreateDynamic(account, properties);
        return new DynamicMailSession(this, managedAccount);
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

    // ==================== 受管非阻塞异步发信门面 ====================

    /**
     * 异步发送邮件（基于强类型领域模型 MailMessage，依托受管线程池与四维上下文自动透传）
     *
     * @param message 邮件领域对象
     * @return 包含发送结果回执的异步 CompletableFuture
     */
    public CompletableFuture<MailSendResult> sendAsync(MailMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("MailMessage must not be null");
        }
        return CompletableFuture.supplyAsync(AsyncContext.wrapSupplier(() -> send(message)), executor);
    }

    /**
     * 异步发送邮件（函数式 DSL 方式）
     *
     * @param messageConfigurer 邮件消息配置器
     * @return 包含发送结果回执的异步 CompletableFuture
     */
    public CompletableFuture<MailSendResult> sendAsync(Consumer<MailMessage> messageConfigurer) {
        if (messageConfigurer == null) {
            throw new IllegalArgumentException("messageConfigurer must not be null");
        }
        MailMessage message = new MailMessage();
        messageConfigurer.accept(message);
        return sendAsync(message);
    }

    /**
     * 指定发信账号异步物理发送邮件
     *
     * @param account 发信账号（需已包含物理 Sender）
     * @param message 邮件消息模型
     * @return 包含发送结果回执的异步 CompletableFuture
     */
    public CompletableFuture<MailSendResult> sendAsyncWithAccount(MailAccount account, MailMessage message) {
        if (account == null) {
            throw new IllegalArgumentException("MailAccount must not be null");
        }
        if (message == null) {
            throw new IllegalArgumentException("MailMessage must not be null");
        }
        return CompletableFuture.supplyAsync(AsyncContext.wrapSupplier(() -> sendWithAccount(account, message)), executor);
    }

    // ==================== 纯文本异步发信快捷门面 ====================

    /**
     * 异步发送纯文本邮件（默认账号，单收件人）
     *
     * @param to      单个目标收件人
     * @param subject 邮件主题
     * @param text    纯文本正文
     * @return 包含发送结果回执的异步 CompletableFuture
     */
    public CompletableFuture<MailSendResult> sendTextAsync(String to, String subject, String text) {
        return sendTextAsync(null, to != null ? List.of(to) : List.of(), subject, text);
    }

    /**
     * 异步发送纯文本邮件（指定账号，单收件人）
     *
     * @param accountName 账号逻辑名称
     * @param to          单个目标收件人
     * @param subject     邮件主题
     * @param text        纯文本正文
     * @return 包含发送结果回执的异步 CompletableFuture
     */
    public CompletableFuture<MailSendResult> sendTextAsync(String accountName, String to, String subject, String text) {
        return sendTextAsync(accountName, to != null ? List.of(to) : List.of(), subject, text);
    }

    /**
     * 异步发送纯文本邮件给多个收件人（默认账号）
     *
     * @param to      目标收件人集合
     * @param subject 邮件主题
     * @param text    纯文本正文
     * @return 包含发送结果回执的异步 CompletableFuture
     */
    public CompletableFuture<MailSendResult> sendTextAsync(Collection<String> to, String subject, String text) {
        return sendTextAsync(null, to, subject, text);
    }

    /**
     * 异步发送纯文本邮件给多个收件人（指定账号）
     *
     * @param accountName 账号逻辑名称
     * @param to          目标收件人集合
     * @param subject     邮件主题
     * @param text        纯文本正文
     * @return 包含发送结果回执的异步 CompletableFuture
     */
    public CompletableFuture<MailSendResult> sendTextAsync(String accountName, Collection<String> to, String subject, String text) {
        MailMessage message = new MailMessage()
                .account(accountName)
                .to(to != null ? new ArrayList<>(to) : new ArrayList<>())
                .subject(subject)
                .text(text);
        return sendAsync(message);
    }

    // ==================== 富文本 HTML 异步发信快捷门面 ====================

    /**
     * 异步发送 HTML 富文本邮件（默认账号，单收件人）
     *
     * @param to      单个目标收件人
     * @param subject 邮件主题
     * @param html    HTML 正文
     * @return 包含发送结果回执的异步 CompletableFuture
     */
    public CompletableFuture<MailSendResult> sendHtmlAsync(String to, String subject, String html) {
        return sendHtmlAsync(null, to != null ? List.of(to) : List.of(), subject, html);
    }

    /**
     * 异步发送 HTML 富文本邮件（指定账号，单收件人）
     *
     * @param accountName 账号逻辑名称
     * @param to          单个目标收件人
     * @param subject     邮件主题
     * @param html        HTML 正文
     * @return 包含发送结果回执的异步 CompletableFuture
     */
    public CompletableFuture<MailSendResult> sendHtmlAsync(String accountName, String to, String subject, String html) {
        return sendHtmlAsync(accountName, to != null ? List.of(to) : List.of(), subject, html);
    }

    /**
     * 异步发送 HTML 富文本邮件给多个收件人（默认账号）
     *
     * @param to      目标收件人集合
     * @param subject 邮件主题
     * @param html    HTML 正文
     * @return 包含发送结果回执的异步 CompletableFuture
     */
    public CompletableFuture<MailSendResult> sendHtmlAsync(Collection<String> to, String subject, String html) {
        return sendHtmlAsync(null, to, subject, html);
    }

    /**
     * 异步发送 HTML 富文本邮件给多个收件人（指定账号）
     *
     * @param accountName 账号逻辑名称
     * @param to          目标收件人集合
     * @param subject     邮件主题
     * @param html        HTML 正文
     * @return 包含发送结果回执的异步 CompletableFuture
     */
    public CompletableFuture<MailSendResult> sendHtmlAsync(String accountName, Collection<String> to, String subject, String html) {
        MailMessage message = new MailMessage()
                .account(accountName)
                .to(to != null ? new ArrayList<>(to) : new ArrayList<>())
                .subject(subject)
                .html(html);
        return sendAsync(message);
    }

    // ==================== 模板动态渲染异步发信快捷门面 ====================

    /**
     * 异步发送模板渲染邮件（默认账号，单收件人）
     *
     * @param to           单个目标收件人
     * @param subject      邮件主题
     * @param templateName 模板名称（如 welcome、mail/verify-code 或内联模板字符串）
     * @param model        数据模型字典
     * @return 包含发送结果回执的异步 CompletableFuture
     */
    public CompletableFuture<MailSendResult> sendTemplateAsync(String to, String subject, String templateName, Map<String, Object> model) {
        return sendTemplateAsync(null, to != null ? List.of(to) : List.of(), subject, templateName, model);
    }

    /**
     * 异步发送模板渲染邮件（指定账号，单收件人）
     *
     * @param accountName  账号逻辑名称
     * @param to           单个目标收件人
     * @param subject      邮件主题
     * @param templateName 模板名称
     * @param model        数据模型字典
     * @return 包含发送结果回执的异步 CompletableFuture
     */
    public CompletableFuture<MailSendResult> sendTemplateAsync(String accountName, String to, String subject, String templateName, Map<String, Object> model) {
        return sendTemplateAsync(accountName, to != null ? List.of(to) : List.of(), subject, templateName, model);
    }

    /**
     * 异步发送模板渲染邮件给多个收件人（默认账号）
     *
     * @param to           目标收件人集合
     * @param subject      邮件主题
     * @param templateName 模板名称
     * @param model        数据模型字典
     * @return 包含发送结果回执的异步 CompletableFuture
     */
    public CompletableFuture<MailSendResult> sendTemplateAsync(Collection<String> to, String subject, String templateName, Map<String, Object> model) {
        return sendTemplateAsync(null, to, subject, templateName, model);
    }

    /**
     * 异步发送模板渲染邮件给多个收件人（指定账号）
     *
     * @param accountName  账号逻辑名称
     * @param to           目标收件人集合
     * @param subject      邮件主题
     * @param templateName 模板名称
     * @param model        数据模型字典
     * @return 包含发送结果回执的异步 CompletableFuture
     */
    public CompletableFuture<MailSendResult> sendTemplateAsync(String accountName, Collection<String> to, String subject, String templateName, Map<String, Object> model) {
        String renderedHtml = templateRenderer.render(templateName, model);
        return sendHtmlAsync(accountName, to, subject, renderedHtml);
    }

    // ==================== 本地事务感知发信门面 (sendAfterCommit) ====================

    /**
     * 事务提交后发信（当且仅当当前 Spring 物理事务 Commit 成功后同步触发，事务回滚则自动取消防幽灵邮件）
     * 若当前处于非事务上下文或未激活事务同步器，则平滑降级为立即执行发信。
     *
     * @param message 邮件消息模型
     */
    public void sendAfterCommit(MailMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("MailMessage must not be null");
        }
        if (isTxSynchronizationActive()) {
            registerAfterCommitSynchronization(message, () -> send(message), false);
        } else {
            send(message);
        }
    }

    /**
     * 指定发信账号在物理事务提交后发信
     *
     * @param account 发信账号模型
     * @param message 邮件消息模型
     */
    public void sendAfterCommitWithAccount(MailAccount account, MailMessage message) {
        if (account == null) {
            throw new IllegalArgumentException("MailAccount must not be null");
        }
        if (message == null) {
            throw new IllegalArgumentException("MailMessage must not be null");
        }
        if (isTxSynchronizationActive()) {
            registerAfterCommitSynchronization(message, () -> sendWithAccount(account, message), false);
        } else {
            sendWithAccount(account, message);
        }
    }

    /**
     * 事务提交后发信（函数式 DSL 方式）
     *
     * @param messageConfigurer 邮件消息配置器
     */
    public void sendAfterCommit(Consumer<MailMessage> messageConfigurer) {
        if (messageConfigurer == null) {
            throw new IllegalArgumentException("messageConfigurer must not be null");
        }
        MailMessage message = new MailMessage();
        messageConfigurer.accept(message);
        sendAfterCommit(message);
    }

    /**
     * 事务提交后异步发信（当且仅当当前 Spring 物理事务 Commit 成功后在受管线程池中触发）
     * 若当前处于非事务上下文或未激活事务同步器，则平滑降级为立即执行异步发信。
     *
     * @param message 邮件消息模型
     */
    public void sendAsyncAfterCommit(MailMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("MailMessage must not be null");
        }
        if (isTxSynchronizationActive()) {
            registerAfterCommitSynchronization(message, () -> send(message), true);
        } else {
            sendAsync(message);
        }
    }

    /**
     * 指定发信账号在物理事务提交后异步发信
     *
     * @param account 发信账号模型
     * @param message 邮件消息模型
     */
    public void sendAsyncAfterCommitWithAccount(MailAccount account, MailMessage message) {
        if (account == null) {
            throw new IllegalArgumentException("MailAccount must not be null");
        }
        if (message == null) {
            throw new IllegalArgumentException("MailMessage must not be null");
        }
        if (isTxSynchronizationActive()) {
            registerAfterCommitSynchronization(message, () -> sendWithAccount(account, message), true);
        } else {
            sendAsyncWithAccount(account, message);
        }
    }

    /**
     * 事务提交后异步发信（函数式 DSL 方式）
     *
     * @param messageConfigurer 邮件消息配置器
     */
    public void sendAsyncAfterCommit(Consumer<MailMessage> messageConfigurer) {
        if (messageConfigurer == null) {
            throw new IllegalArgumentException("messageConfigurer must not be null");
        }
        MailMessage message = new MailMessage();
        messageConfigurer.accept(message);
        sendAsyncAfterCommit(message);
    }

    // ==================== 事务感知发信快捷门面 ====================

    public void sendTextAfterCommit(String to, String subject, String text) {
        sendTextAfterCommit(null, to != null ? List.of(to) : List.of(), subject, text);
    }

    public void sendTextAfterCommit(String accountName, String to, String subject, String text) {
        sendTextAfterCommit(accountName, to != null ? List.of(to) : List.of(), subject, text);
    }

    public void sendTextAfterCommit(Collection<String> to, String subject, String text) {
        sendTextAfterCommit(null, to, subject, text);
    }

    public void sendTextAfterCommit(String accountName, Collection<String> to, String subject, String text) {
        MailMessage message = new MailMessage()
                .account(accountName)
                .to(to != null ? new ArrayList<>(to) : new ArrayList<>())
                .subject(subject)
                .text(text);
        sendAfterCommit(message);
    }

    public void sendHtmlAfterCommit(String to, String subject, String html) {
        sendHtmlAfterCommit(null, to != null ? List.of(to) : List.of(), subject, html);
    }

    public void sendHtmlAfterCommit(String accountName, String to, String subject, String html) {
        sendHtmlAfterCommit(accountName, to != null ? List.of(to) : List.of(), subject, html);
    }

    public void sendHtmlAfterCommit(Collection<String> to, String subject, String html) {
        sendHtmlAfterCommit(null, to, subject, html);
    }

    public void sendHtmlAfterCommit(String accountName, Collection<String> to, String subject, String html) {
        MailMessage message = new MailMessage()
                .account(accountName)
                .to(to != null ? new ArrayList<>(to) : new ArrayList<>())
                .subject(subject)
                .html(html);
        sendAfterCommit(message);
    }

    public void sendTemplateAfterCommit(String to, String subject, String templateName, Map<String, Object> model) {
        sendTemplateAfterCommit(null, to != null ? List.of(to) : List.of(), subject, templateName, model);
    }

    public void sendTemplateAfterCommit(String accountName, String to, String subject, String templateName, Map<String, Object> model) {
        sendTemplateAfterCommit(accountName, to != null ? List.of(to) : List.of(), subject, templateName, model);
    }

    public void sendTemplateAfterCommit(Collection<String> to, String subject, String templateName, Map<String, Object> model) {
        sendTemplateAfterCommit(null, to, subject, templateName, model);
    }

    public void sendTemplateAfterCommit(String accountName, Collection<String> to, String subject, String templateName, Map<String, Object> model) {
        String renderedHtml = templateRenderer.render(templateName, model);
        sendHtmlAfterCommit(accountName, to, subject, renderedHtml);
    }

    public void sendTextAsyncAfterCommit(String to, String subject, String text) {
        sendTextAsyncAfterCommit(null, to != null ? List.of(to) : List.of(), subject, text);
    }

    public void sendTextAsyncAfterCommit(String accountName, String to, String subject, String text) {
        sendTextAsyncAfterCommit(accountName, to != null ? List.of(to) : List.of(), subject, text);
    }

    public void sendTextAsyncAfterCommit(Collection<String> to, String subject, String text) {
        sendTextAsyncAfterCommit(null, to, subject, text);
    }

    public void sendTextAsyncAfterCommit(String accountName, Collection<String> to, String subject, String text) {
        MailMessage message = new MailMessage()
                .account(accountName)
                .to(to != null ? new ArrayList<>(to) : new ArrayList<>())
                .subject(subject)
                .text(text);
        sendAsyncAfterCommit(message);
    }

    public void sendHtmlAsyncAfterCommit(String to, String subject, String html) {
        sendHtmlAsyncAfterCommit(null, to != null ? List.of(to) : List.of(), subject, html);
    }

    public void sendHtmlAsyncAfterCommit(String accountName, String to, String subject, String html) {
        sendHtmlAsyncAfterCommit(accountName, to != null ? List.of(to) : List.of(), subject, html);
    }

    public void sendHtmlAsyncAfterCommit(Collection<String> to, String subject, String html) {
        sendHtmlAsyncAfterCommit(null, to, subject, html);
    }

    public void sendHtmlAsyncAfterCommit(String accountName, Collection<String> to, String subject, String html) {
        MailMessage message = new MailMessage()
                .account(accountName)
                .to(to != null ? new ArrayList<>(to) : new ArrayList<>())
                .subject(subject)
                .html(html);
        sendAsyncAfterCommit(message);
    }

    public void sendTemplateAsyncAfterCommit(String to, String subject, String templateName, Map<String, Object> model) {
        sendTemplateAsyncAfterCommit(null, to != null ? List.of(to) : List.of(), subject, templateName, model);
    }

    public void sendTemplateAsyncAfterCommit(String accountName, String to, String subject, String templateName, Map<String, Object> model) {
        sendTemplateAsyncAfterCommit(accountName, to != null ? List.of(to) : List.of(), subject, templateName, model);
    }

    public void sendTemplateAsyncAfterCommit(Collection<String> to, String subject, String templateName, Map<String, Object> model) {
        sendTemplateAsyncAfterCommit(null, to, subject, templateName, model);
    }

    public void sendTemplateAsyncAfterCommit(String accountName, Collection<String> to, String subject, String templateName, Map<String, Object> model) {
        String renderedHtml = templateRenderer.render(templateName, model);
        sendHtmlAsyncAfterCommit(accountName, to, subject, renderedHtml);
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
        return account != null ? account.getDomain() : "ateng-boot.local";
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

    private void publishEvent(MailSendEvent event) {
        if (eventPublisher != null) {
            try {
                eventPublisher.publishEvent(event);
            } catch (Throwable t) {
                log.warn("Failed to publish mail event [{}]: {}", event.getClass().getSimpleName(), t.getMessage(), t);
            }
        }
    }

    private void triggerFailureSafetyNet(MailMessage message, String maskedTo, Throwable cause) {
        log.error("[MailSafetyNet] Physical mail delivery to [{}] failed critically for subject [{}]: {}",
                maskedTo, message.getSubject(), cause.getMessage(), cause);

        publishEvent(new MailSendFailedEvent(this, message, cause));

        if (!failureHandlers.isEmpty()) {
            for (MailSendFailureHandler handler : failureHandlers) {
                try {
                    handler.onFailure(message, cause);
                } catch (Throwable handlerEx) {
                    log.error("[MailSafetyNet] MailSendFailureHandler [{}] executed with error: {}",
                            handler.getClass().getName(), handlerEx.getMessage(), handlerEx);
                }
            }
        }
    }

    private boolean isRetryableException(Throwable throwable) {
        if (throwable instanceof MailAuthenticationException) {
            return false;
        }
        if (throwable instanceof MailSendException mse) {
            if (mse.getResultCode() == MailResultCode.MAIL_AUTHENTICATION_ERROR
                    || mse.getResultCode() == MailResultCode.MAIL_ACCOUNT_NOT_FOUND
                    || mse.getResultCode() == MailResultCode.MAIL_RECIPIENT_EMPTY
                    || mse.getResultCode() == MailResultCode.MAIL_ATTACHMENT_SIZE_EXCEEDED
                    || mse.getResultCode() == MailResultCode.MAIL_TOTAL_SIZE_EXCEEDED) {
                return false;
            }
        }
        return true;
    }

    private void applyInterceptors(MailMessage message) {
        if (!interceptors.isEmpty()) {
            for (MailSendInterceptor interceptor : interceptors) {
                try {
                    boolean proceed = interceptor.preSend(message);
                    if (!proceed) {
                        log.warn("Mail sending blocked by interceptor [{}], subject: [{}]",
                                interceptor.getClass().getSimpleName(), message.getSubject());
                        throw new MailSendException(MailResultCode.MAIL_SEND_ERROR,
                                "邮件发送被拦截器阻断: " + interceptor.getClass().getSimpleName());
                    }
                } catch (MailSendException mse) {
                    throw mse;
                } catch (Throwable t) {
                    throw new MailSendException(MailResultCode.MAIL_SEND_ERROR,
                            "邮件拦截器执行异常: " + t.getMessage(), t);
                }
            }
        }
    }

    private void registerAfterCommitSynchronization(MailMessage message, Runnable deliveryTask, boolean isAsync) {
        AsyncContext.ContextSnapshot parentSnapshot = AsyncContext.ContextSnapshot.capture();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (isAsync) {
                    executor.execute(() -> executeDeliveryWithContext(parentSnapshot, deliveryTask));
                } else {
                    executeDeliveryWithContext(parentSnapshot, deliveryTask);
                }
            }

            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    try {
                        String masked = (message.getTo() != null)
                                ? message.getTo().stream().filter(Objects::nonNull).map(DesensitizeUtil::email).collect(Collectors.joining(","))
                                : "";
                        log.warn("Transaction uncommitted or rolled back (status: {}), canceled {}mail delivery to [{}], subject: [{}]",
                                status, isAsync ? "async " : "", masked, message.getSubject());
                    } finally {
                        // 确定性资源清理：物理事务未提交或回滚时，释放邮件消息中挂载的所有流句柄与临时文件
                        message.close();
                    }
                }
            }
        });
    }

    private void executeDeliveryWithContext(AsyncContext.ContextSnapshot parentSnapshot, Runnable deliveryTask) {
        AsyncContext.ContextSnapshot previous = AsyncContext.ContextSnapshot.capturePrevious();
        try {
            parentSnapshot.apply();
            deliveryTask.run();
        } catch (Throwable t) {
            log.warn("Delivery after commit failed: {}", t.getMessage());
        } finally {
            previous.apply();
        }
    }

    private boolean isTxSynchronizationActive() {
        return SPRING_TX_PRESENT && TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive();
    }

    @Override
    public void destroy() {
        if (defaultExecutorCreated && executor instanceof ThreadPoolTaskExecutor taskExecutor) {
            log.info("Shutting down default mail ThreadPoolTaskExecutor");
            taskExecutor.destroy();
        }
    }

    private static ThreadPoolTaskExecutor createDefaultExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(2, Runtime.getRuntime().availableProcessors()));
        executor.setMaxPoolSize(Math.max(4, Runtime.getRuntime().availableProcessors() * 2));
        executor.setQueueCapacity(1024);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("ateng-mail-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setTaskDecorator(new ContextCopyingTaskDecorator());
        executor.initialize();
        return executor;
    }

    private InputStreamSource wrapBoundedSource(String filename, InputStreamSource originalSource, long maxBytes,
                                                AtomicReference<MailSendException> sizeLimitExceptionRef) {
        return () -> {
            InputStream in = originalSource.getInputStream();
            return new BoundedInputStream(in, maxBytes, filename, sizeLimitExceptionRef);
        };
    }

    private MailSendException findNestedMailSendException(Throwable throwable) {
        if (throwable instanceof org.springframework.mail.MailSendException springEx) {
            Exception[] messageExceptions = springEx.getMessageExceptions();
            if (messageExceptions != null) {
                for (Exception me : messageExceptions) {
                    MailSendException found = findNestedMailSendException(me);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof MailSendException mse) {
                return mse;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * 流式附件读取容量上限守护流，严密防范未知容量裸流耗尽物理内存
     */
    private static class BoundedInputStream extends FilterInputStream {
        private final long maxBytes;
        private final String filename;
        private final AtomicReference<MailSendException> sizeLimitExceptionRef;
        private long bytesRead = 0;

        public BoundedInputStream(InputStream in, long maxBytes, String filename,
                                  AtomicReference<MailSendException> sizeLimitExceptionRef) {
            super(in);
            this.maxBytes = maxBytes;
            this.filename = filename;
            this.sizeLimitExceptionRef = sizeLimitExceptionRef;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                checkLimit(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b) throws IOException {
            return read(b, 0, b.length);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = super.read(b, off, len);
            if (read > 0) {
                checkLimit(read);
            }
            return read;
        }

        @Override
        public long transferTo(OutputStream out) throws IOException {
            byte[] buffer = new byte[8192];
            long transferred = 0;
            int read;
            while ((read = this.read(buffer, 0, buffer.length)) >= 0) {
                out.write(buffer, 0, read);
                transferred += read;
            }
            return transferred;
        }

        @Override
        public byte[] readAllBytes() throws IOException {
            byte[] bytes = super.readAllBytes();
            checkLimit(bytes.length);
            return bytes;
        }

        @Override
        public byte[] readNBytes(int len) throws IOException {
            byte[] bytes = super.readNBytes(len);
            checkLimit(bytes.length);
            return bytes;
        }

        @Override
        public int readNBytes(byte[] b, int off, int len) throws IOException {
            int read = super.readNBytes(b, off, len);
            if (read > 0) {
                checkLimit(read);
            }
            return read;
        }

        private void checkLimit(int newlyRead) throws IOException {
            bytesRead += newlyRead;
            if (bytesRead > maxBytes) {
                MailSendException ex = new MailSendException(MailResultCode.MAIL_ATTACHMENT_SIZE_EXCEEDED,
                        "邮件附件 [" + filename + "] 流式读取体积超过单个附件上限 (" + maxBytes + " 字节)");
                if (sizeLimitExceptionRef != null) {
                    sizeLimitExceptionRef.compareAndSet(null, ex);
                }
                throw ex;
            }
        }
    }
}
