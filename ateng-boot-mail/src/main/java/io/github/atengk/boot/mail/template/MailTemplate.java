package io.github.atengk.boot.mail.template;

import io.github.atengk.boot.core.autoconfigure.AsyncExecutorFactory;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.properties.AsyncPoolProperties;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.core.util.FileUtil;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.RetryUtil;
import io.github.atengk.boot.mail.event.MailSendEvent;
import io.github.atengk.boot.mail.event.MailSendFailedEvent;
import io.github.atengk.boot.mail.event.MailSendingEvent;
import io.github.atengk.boot.mail.event.MailSentEvent;
import io.github.atengk.boot.mail.exception.MailResultCode;
import io.github.atengk.boot.mail.exception.MailSendException;
import io.github.atengk.boot.mail.handler.MailSendFailureHandler;
import io.github.atengk.boot.mail.interceptor.MailSandboxInterceptor;
import io.github.atengk.boot.mail.interceptor.MailSendInterceptor;
import io.github.atengk.boot.mail.model.BatchMailSendResult;
import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.model.MailAttachment;
import io.github.atengk.boot.mail.model.MailInlineResource;
import io.github.atengk.boot.mail.model.MailMessage;
import io.github.atengk.boot.mail.model.MailSendResult;
import io.github.atengk.boot.mail.model.MailSendResultItem;
import io.github.atengk.boot.mail.model.TrackableMimeMessage;
import io.github.atengk.boot.mail.properties.MailProperties;
import io.github.atengk.boot.mail.properties.MailRetryProperties;
import io.github.atengk.boot.mail.provider.MailAccountProvider;
import io.github.atengk.boot.mail.registry.MailAccountRegistry;
import io.github.atengk.boot.mail.resolver.MailAccountResolver;
import io.github.atengk.boot.mail.resolver.TenantMailAccountResolver;
import io.github.atengk.boot.mail.template.renderer.MailTemplateRenderer;
import io.github.atengk.boot.mail.template.renderer.SimpleMailTemplateRenderer;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.io.InputStreamSource;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.ClassUtils;

import java.io.FilterInputStream;
import java.time.LocalDateTime;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
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
    private final MailAccountProvider accountProvider;

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
        this(accountRegistry, properties, new SimpleMailTemplateRenderer(), new TenantMailAccountResolver(), null, null, null, null, null);
    }

    public MailTemplate(MailAccountRegistry accountRegistry, MailProperties properties, MailTemplateRenderer templateRenderer) {
        this(accountRegistry, properties, templateRenderer, new TenantMailAccountResolver(), null, null, null, null, null);
    }

    public MailTemplate(MailAccountRegistry accountRegistry, MailProperties properties, MailTemplateRenderer templateRenderer, MailAccountResolver accountResolver) {
        this(accountRegistry, properties, templateRenderer, accountResolver, null, null, null, null, null);
    }

    public MailTemplate(MailAccountRegistry accountRegistry, MailProperties properties, MailTemplateRenderer templateRenderer, MailAccountResolver accountResolver, MailAccountProvider accountProvider) {
        this(accountRegistry, properties, templateRenderer, accountResolver, accountProvider, null, null, null, null);
    }

    public MailTemplate(MailAccountRegistry accountRegistry, MailProperties properties, MailTemplateRenderer templateRenderer, MailAccountResolver accountResolver, Executor executor) {
        this(accountRegistry, properties, templateRenderer, accountResolver, null, executor, null, null, null);
    }

    public MailTemplate(MailAccountRegistry accountRegistry, MailProperties properties, MailTemplateRenderer templateRenderer, MailAccountResolver accountResolver, Executor executor, ApplicationEventPublisher eventPublisher, List<MailSendFailureHandler> failureHandlers) {
        this(accountRegistry, properties, templateRenderer, accountResolver, null, executor, eventPublisher, failureHandlers, null);
    }

    public MailTemplate(MailAccountRegistry accountRegistry, MailProperties properties, MailTemplateRenderer templateRenderer, MailAccountResolver accountResolver, Executor executor, ApplicationEventPublisher eventPublisher, List<MailSendFailureHandler> failureHandlers, List<MailSendInterceptor> interceptors) {
        this(accountRegistry, properties, templateRenderer, accountResolver, null, executor, eventPublisher, failureHandlers, interceptors);
    }

    public MailTemplate(MailAccountRegistry accountRegistry, MailProperties properties, MailTemplateRenderer templateRenderer, MailAccountResolver accountResolver, MailAccountProvider accountProvider, Executor executor, ApplicationEventPublisher eventPublisher, List<MailSendFailureHandler> failureHandlers, List<MailSendInterceptor> interceptors) {
        this.accountRegistry = Objects.requireNonNull(accountRegistry, "accountRegistry must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.templateRenderer = templateRenderer != null ? templateRenderer : new SimpleMailTemplateRenderer();
        this.accountResolver = accountResolver != null ? accountResolver : new TenantMailAccountResolver();
        this.accountProvider = accountProvider;
        boolean asyncEnabled = properties.getAsync() == null || properties.getAsync().isEnabled();
        if (executor != null) {
            this.executor = executor;
            this.defaultExecutorCreated = false;
        } else if (asyncEnabled) {
            this.executor = createDefaultExecutor(properties.getAsync());
            this.defaultExecutorCreated = true;
        } else {
            this.executor = null;
            this.defaultExecutorCreated = false;
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
        try {
            requireNonEmptyRecipients(message);
            validateMessageAddresses(null, message);
            MailAccount account = resolveAccount(message);
            return sendWithAccount(account, message);
        } finally {
            message.close();
        }
    }

    private void requireNonEmptyRecipients(MailMessage message) {
        List<String> to = message.getTo();
        if (to == null || to.isEmpty()) {
            throw new MailSendException(MailResultCode.MAIL_RECIPIENT_EMPTY, "收件人列表不能为空");
        }
        boolean hasNonBlank = to.stream().anyMatch(addr -> addr != null && !addr.isBlank());
        if (!hasNonBlank) {
            throw new MailSendException(MailResultCode.MAIL_RECIPIENT_EMPTY, "收件人列表不能为空");
        }
    }

    private MailAccount resolveAccount(MailMessage message) {
        String explicitAccountName = message.getAccount();
        if (explicitAccountName != null) {
            // 显式指定账号：三级检索（静态表 -> 动态LRU -> Provider懒加载）
            MailAccount account = findAccount(explicitAccountName);
            if (account == null) {
                // 双轨防张冠李戴红线：显式指定未命中必须立即 Fail-Fast，严禁回退错发默认系统账号
                throw new MailSendException(MailResultCode.MAIL_ACCOUNT_NOT_FOUND,
                        "未找到指定的发信账号: [" + explicitAccountName + "]");
            }
            return account;
        }

        // 未显式指定账号：触发动态路由解析器（如多租户上下文）
        String resolvedName = accountResolver.resolveAccount(message);
        if (resolvedName != null && !resolvedName.isBlank()) {
            MailAccount account = findAccount(resolvedName);
            if (account != null) {
                return account;
            }
        }

        // 隐式推断未命中对应账号时，平滑安全回退至全局默认发信账号
        return accountRegistry.getDefault();
    }

    /**
     * 按账号名称检索发信账号（顺序：静态注册表 -> 动态 LRU 缓存 -> MailAccountProvider 外部懒加载）
     *
     * @param name 账号逻辑标识
     * @return 绑定物理 Sender 的发信账号，若未找到返回 null
     */
    private MailAccount findAccount(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        // 1. 静态注册表
        MailAccount account = accountRegistry.get(name);
        if (account != null) {
            return account;
        }
        // 2. 动态 LRU 缓存
        account = accountRegistry.getDynamic(name);
        if (account != null) {
            return account;
        }
        // 3. MailAccountProvider 外部懒加载
        if (accountProvider != null) {
            MailAccount loaded = accountProvider.loadAccount(name);
            if (loaded != null) {
                return accountRegistry.getOrCreateDynamic(name, loaded, properties);
            }
        }
        return null;
    }

    /**
     * 指定发信账号物理发送邮件
     *
     * @param account 发信账号（需已包含物理 Sender）
     * @param message 邮件消息模型
     * @return 发送结果回执
     */
    public MailSendResult sendWithAccount(MailAccount account, MailMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("MailMessage must not be null");
        }
        if (account == null) {
            message.close();
            throw new IllegalArgumentException("MailAccount must not be null");
        }

        String maskedTo = maskRecipients(message);

        try {
            requireNonEmptyRecipients(message);
            validateMessageAddresses(account, message);
            sanitizeMessageHeaders(message);

            // 0. 合规审计归档密送安全追加
            applyArchiveBcc(account, message);

            // 容量防爆安全校验
            enforceSizeLimits(message);

            // 发信拦截器链前置执行
            applyInterceptors(message);

            // 拦截器可能改写收件人列表或标头，发送前二次兜底校验与清洗
            requireNonEmptyRecipients(message);
            validateRecipientAddresses(message);
            sanitizeMessageHeaders(message);

            // 重新计算拦截器改写后的脱敏收件人（如沙箱重定向）
            String currentMaskedTo = maskRecipients(message);

            String traceId = resolveTraceId();
            String domain = resolveDomain(account);

            // 检查是否命中沙箱纯日志模拟发信模式
            if (isSandboxLogOnly(message)) {
                String messageId = "<sandbox-log-" + traceId + "@" + domain + ">";
                log.info("[MAIL-SANDBOX-LOG-ONLY] Simulated email delivery successfully without network connection. Message-ID: [{}], To: [{}], Subject: [{}]",
                        messageId, currentMaskedTo, message.getSubject());

                publishEvent(new MailSendingEvent(this, message));

                MailSendResult result = MailSendResult.success(messageId,
                        message.getTo() != null ? new ArrayList<>(message.getTo()) : Collections.emptyList(),
                        Duration.ofMillis(1));

                publishEvent(new MailSentEvent(this, message, result));
                return result;
            }

            String messageId = "<" + traceId + "-" + System.nanoTime() + "@" + domain + ">";

            publishEvent(new MailSendingEvent(this, message));

            MailSendResult result;
            MailRetryProperties retryProps = properties.getRetry();
            boolean repeatableMessage = isRepeatableMessage(message);
            if (retryProps != null && retryProps.isEnabled() && !repeatableMessage) {
                log.warn("Message [{}] contains non-repeatable InputStream resource(s), disabling retry to prevent corrupted empty attachments",
                        message.getSubject());
            }
            if (retryProps != null && retryProps.isEnabled() && repeatableMessage) {
                RetryUtil.RetryConfig retryConfig = RetryUtil.RetryConfig.builder()
                        .maxAttempts(retryProps.getMaxAttempts())
                        .delay(retryProps.getDelay())
                        .multiplier(retryProps.getMultiplier())
                        .maxDelay(retryProps.getMaxDelay())
                        .jitter(retryProps.isJitter())
                        .retryPredicate(this::isRetryableException)
                        .onRetry((attempt, error) -> log.warn("Sending email to [{}] failed on attempt {}, preparing retry. Reason: {}",
                                currentMaskedTo, attempt, error.getMessage()))
                        .build();
                result = RetryUtil.execute(() -> doSendPhysical(account, message, currentMaskedTo, traceId, messageId), retryConfig);
            } else {
                result = doSendPhysical(account, message, currentMaskedTo, traceId, messageId);
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
            String explicitFromName = (message.getFromName() != null && !message.getFromName().isBlank())
                    ? message.getFromName()
                    : account.getDefaultFromName();

            if (fromAddress != null && !fromAddress.isBlank()) {
                InternetAddress parsedFrom = parseSingleInternetAddress(fromAddress);
                if (parsedFrom != null) {
                    String effectivePersonal = (message.getFromName() != null && !message.getFromName().isBlank())
                            ? message.getFromName()
                            : ((parsedFrom.getPersonal() != null && !parsedFrom.getPersonal().isBlank())
                            ? parsedFrom.getPersonal()
                            : account.getDefaultFromName());
                    if (effectivePersonal != null && !effectivePersonal.isBlank()) {
                        helper.setFrom(new InternetAddress(parsedFrom.getAddress(), MailMessage.sanitizeHeaderText(effectivePersonal), StandardCharsets.UTF_8.name()));
                    } else {
                        helper.setFrom(parsedFrom.getAddress());
                    }
                } else if (explicitFromName != null && !explicitFromName.isBlank()) {
                    helper.setFrom(new InternetAddress(fromAddress, MailMessage.sanitizeHeaderText(explicitFromName), StandardCharsets.UTF_8.name()));
                } else {
                    helper.setFrom(fromAddress);
                }
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
            helper.setSubject(message.getSubject() != null ? MailMessage.sanitizeHeaderText(message.getSubject()) : "");

            // 4. 自定义邮件标头
            if (message.getHeaders() != null) {
                for (Map.Entry<String, String> header : message.getHeaders().entrySet()) {
                    if (header.getKey() != null && header.getValue() != null) {
                        String cleanKey = MailMessage.sanitizeHeaderText(header.getKey()).trim();
                        if (!cleanKey.isBlank()) {
                            helper.getMimeMessage().setHeader(cleanKey, MailMessage.sanitizeHeaderText(header.getValue()));
                        }
                    }
                }
            }

            // 4.1 强类型邮件优先级标头注入
            if (message.getPriority() != null) {
                helper.getMimeMessage().setHeader("X-Priority", message.getPriority().getXPriority());
                helper.getMimeMessage().setHeader("Importance", message.getPriority().getImportance());
                helper.getMimeMessage().setHeader("X-MSMail-Priority", message.getPriority().getImportance());
            }

            // 4.2 阅读回执标头注入 (RFC 3798)
            String targetReceiptTo = (message.getReadReceiptTo() != null && !message.getReadReceiptTo().isBlank())
                    ? message.getReadReceiptTo().trim()
                    : (message.isRequestReadReceipt() ? fromAddress : null);
            if (targetReceiptTo != null && !targetReceiptTo.isBlank()) {
                helper.getMimeMessage().setHeader("Disposition-Notification-To", targetReceiptTo);
            }

            // 5. 正文内容装配
            helper.setText(message.getText() != null ? message.getText() : "", message.isHtml());

            // 6. 内联嵌入资源（必须在 helper.setText 之后执行）
            AtomicReference<MailSendException> sizeLimitException = new AtomicReference<>();
            long maxAttachmentBytes = (properties.getMaxAttachmentSize() != null)
                    ? properties.getMaxAttachmentSize().toBytes()
                    : Long.MAX_VALUE;
            long maxTotalBytes = (properties.getMaxTotalSize() != null)
                    ? properties.getMaxTotalSize().toBytes()
                    : Long.MAX_VALUE;
            long knownTotalBytes = computeKnownTotalBytes(message);
            CumulativeSizeTracker sizeTracker = new CumulativeSizeTracker(knownTotalBytes, maxTotalBytes, sizeLimitException);

            if (message.hasInlines()) {
                for (MailInlineResource inline : message.getInlines()) {
                    if (inline == null || inline.isSystemJunk()) {
                        continue;
                    }
                    InputStreamSource source = inline.getSource();
                    if (inline.getSize() == null && (maxAttachmentBytes < Long.MAX_VALUE || maxTotalBytes < Long.MAX_VALUE) && source != null) {
                        source = wrapBoundedSource(inline.getContentId(), source, maxAttachmentBytes, sizeTracker, sizeLimitException);
                    }
                    helper.addInline(inline.getContentId(), source, inline.getContentType());
                }
            }

            // 7. 附件流式挂载
            if (message.hasAttachments()) {
                for (MailAttachment attachment : message.getAttachments()) {
                    if (attachment == null || attachment.isSystemJunk() || FileUtil.isSystemJunkFile(attachment.getFilename())) {
                        continue;
                    }
                    String safeFilename = MailAttachment.cleanFilename(attachment.getFilename());
                    InputStreamSource source = attachment.getSource();
                    // 若附件容量预先未知，挂载流式计数防爆数据源，严防无界裸流耗尽物理内存
                    if (attachment.getSize() == null && (maxAttachmentBytes < Long.MAX_VALUE || maxTotalBytes < Long.MAX_VALUE) && source != null) {
                        source = wrapBoundedSource(safeFilename, source, maxAttachmentBytes, sizeTracker, sizeLimitException);
                    }
                    helper.addAttachment(safeFilename, source, attachment.getContentType());
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
        return withConfiguredMessage(messageConfigurer, this::send);
    }

    // ==================== 批量个性化发信与邮件合并门面 ====================

    /**
     * 批量个性化发送邮件（受控并发分片投递与 Fail-Safe 独立容错聚合）
     *
     * @param messages 邮件消息列表
     * @return 批量发信聚合回执
     */
    public BatchMailSendResult sendBatch(List<MailMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return BatchMailSendResult.builder()
                    .totalCount(0)
                    .successCount(0)
                    .failureCount(0)
                    .duration(Duration.ZERO)
                    .items(Collections.emptyList())
                    .build();
        }

        long start = System.nanoTime();
        int total = messages.size();
        int concurrency = resolveBatchConcurrency();

        MailSendResultItem[] results = new MailSendResultItem[total];
        Semaphore semaphore = new Semaphore(concurrency);
        List<CompletableFuture<Void>> futures = new ArrayList<>(total);
        Executor batchExecutor = this.executor != null ? this.executor : Runnable::run;

        for (int i = 0; i < total; i++) {
            final int index = i;
            final MailMessage message = messages.get(i);

            try {
                CompletableFuture<Void> future = CompletableFuture.runAsync(AsyncContext.wrap(() -> {
                    try {
                        semaphore.acquire();
                        try {
                            MailSendResult result = send(message);
                            results[index] = MailSendResultItem.success(index, message, result);
                        } catch (Throwable t) {
                            // Fail-Safe 容错：单个发信失败不阻断批次内其余邮件，详细记录失败根因
                            results[index] = MailSendResultItem.failure(index, message, t.getMessage(), t);
                        } finally {
                            semaphore.release();
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        if (message != null) {
                            message.close();
                        }
                        results[index] = MailSendResultItem.failure(index, message, "批量发送任务被中断: " + ie.getMessage(), ie);
                    }
                }), batchExecutor);
                futures.add(future);
            } catch (Throwable t) {
                if (message != null) {
                    message.close();
                }
                results[index] = MailSendResultItem.failure(index, message, "任务提交失败: " + t.getMessage(), t);
            }
        }

        if (!futures.isEmpty()) {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        Duration duration = Duration.ofNanos(System.nanoTime() - start);
        List<MailSendResultItem> itemList = new ArrayList<>(total);
        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < total; i++) {
            MailSendResultItem item = results[i];
            if (item != null) {
                itemList.add(item);
                if (item.isSuccess()) {
                    successCount++;
                } else {
                    failureCount++;
                }
            } else {
                MailSendResultItem unknown = MailSendResultItem.failure(i, messages.get(i), "未获取到执行结果", null);
                itemList.add(unknown);
                failureCount++;
            }
        }

        log.info("Batch mail merge completed in {} ms: total={}, success={}, failure={}",
                duration.toMillis(), total, successCount, failureCount);

        return BatchMailSendResult.builder()
                .totalCount(total)
                .successCount(successCount)
                .failureCount(failureCount)
                .duration(duration)
                .items(itemList)
                .build();
    }

    /**
     * 异步批量个性化发送邮件
     *
     * @param messages 邮件消息列表
     * @return 包含批量发信聚合回执的 CompletableFuture
     */
    public CompletableFuture<BatchMailSendResult> sendBatchAsync(List<MailMessage> messages) {
        try {
            Executor asyncExecutor = requireAsyncExecutor();
            return CompletableFuture.supplyAsync(AsyncContext.wrapSupplier(() -> sendBatch(messages)), asyncExecutor);
        } catch (Throwable t) {
            closeAllMessagesQuietly(messages);
            throw t;
        }
    }

    private int resolveBatchConcurrency() {
        if (properties != null && properties.getBatch() != null) {
            int c = properties.getBatch().getConcurrency();
            return Math.max(1, Math.min(32, c));
        }
        return 4;
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

    /**
     * 精准驱逐指定逻辑名称的动态账号缓存及其底层物理 Sender
     *
     * @param accountName 动态账号逻辑标识
     * @return 被移除的动态账号实例，若不存在返回 null
     */
    public MailAccount evictDynamicAccount(String accountName) {
        return accountRegistry.evictDynamic(accountName);
    }

    /**
     * 清空全部动态发件账号缓存并释放底层所有物理 Sender
     */
    public void clearDynamicAccounts() {
        accountRegistry.clearDynamic();
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
     * 渲染模板为富文本 HTML 字符串（自动探测 LocaleContextHolder 国际化语言）
     *
     * @param templateName 模板名称
     * @param model        数据模型字典
     * @return 渲染后的 HTML 字符串
     */
    public String renderTemplate(String templateName, Map<String, Object> model) {
        return renderTemplate(templateName, model, resolveLocale(null));
    }

    /**
     * 根据指定国际化语言渲染模板为富文本 HTML 字符串
     *
     * @param templateName 模板名称
     * @param model        数据模型字典
     * @param locale       目标语言区域（若为 null 则自动探测 LocaleContextHolder）
     * @return 渲染后的 HTML 字符串
     */
    public String renderTemplate(String templateName, Map<String, Object> model, Locale locale) {
        return templateRenderer.render(templateName, model, resolveLocale(locale));
    }

    private Locale resolveLocale(Locale explicitLocale) {
        if (explicitLocale != null) {
            return explicitLocale;
        }
        try {
            Locale contextLocale = LocaleContextHolder.getLocale();
            if (contextLocale != null) {
                return contextLocale;
            }
        } catch (Throwable ignored) {
            // 忽略上下文异常
        }
        return Locale.getDefault();
    }

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
        return sendTemplate(null, to != null ? List.of(to) : List.of(), subject, templateName, model, null);
    }

    /**
     * 发送模板渲染邮件（默认账号，指定国际化区域）
     *
     * @param to           单个目标收件人
     * @param subject      邮件主题
     * @param templateName 模板名称
     * @param model        数据模型字典
     * @param locale       目标国际化区域
     * @return 发送结果回执
     */
    public MailSendResult sendTemplate(String to, String subject, String templateName, Map<String, Object> model, Locale locale) {
        return sendTemplate(null, to != null ? List.of(to) : List.of(), subject, templateName, model, locale);
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
        return sendTemplate(accountName, to != null ? List.of(to) : List.of(), subject, templateName, model, null);
    }

    /**
     * 发送模板渲染邮件（指定账号与国际化区域）
     *
     * @param accountName  账号逻辑名称
     * @param to           单个目标收件人
     * @param subject      邮件主题
     * @param templateName 模板名称
     * @param model        数据模型字典
     * @param locale       目标国际化区域
     * @return 发送结果回执
     */
    public MailSendResult sendTemplate(String accountName, String to, String subject, String templateName, Map<String, Object> model, Locale locale) {
        return sendTemplate(accountName, to != null ? List.of(to) : List.of(), subject, templateName, model, locale);
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
        return sendTemplate(null, to, subject, templateName, model, null);
    }

    /**
     * 发送模板渲染邮件给多个收件人（默认账号，指定国际化区域）
     *
     * @param to           目标收件人集合
     * @param subject      邮件主题
     * @param templateName 模板名称
     * @param model        数据模型字典
     * @param locale       目标国际化区域
     * @return 发送结果回执
     */
    public MailSendResult sendTemplate(Collection<String> to, String subject, String templateName, Map<String, Object> model, Locale locale) {
        return sendTemplate(null, to, subject, templateName, model, locale);
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
        return sendTemplate(accountName, to, subject, templateName, model, null);
    }

    /**
     * 发送模板渲染邮件给多个收件人（指定账号与国际化区域）
     *
     * @param accountName  账号逻辑名称
     * @param to           目标收件人集合
     * @param subject      邮件主题
     * @param templateName 模板名称
     * @param model        数据模型字典
     * @param locale       目标国际化区域
     * @return 发送结果回执
     */
    public MailSendResult sendTemplate(String accountName, Collection<String> to, String subject, String templateName, Map<String, Object> model, Locale locale) {
        String renderedHtml = renderTemplate(templateName, model, locale);
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
        try {
            Executor asyncExecutor = requireAsyncExecutor();
            return CompletableFuture.supplyAsync(AsyncContext.wrapSupplier(() -> send(message)), asyncExecutor);
        } catch (Throwable t) {
            message.close();
            throw t;
        }
    }

    /**
     * 异步发送邮件（函数式 DSL 方式）
     *
     * @param messageConfigurer 邮件消息配置器
     * @return 包含发送结果回执的异步 CompletableFuture
     */
    public CompletableFuture<MailSendResult> sendAsync(Consumer<MailMessage> messageConfigurer) {
        return withConfiguredMessage(messageConfigurer, this::sendAsync);
    }

    /**
     * 指定发信账号异步物理发送邮件
     *
     * @param account 发信账号（需已包含物理 Sender）
     * @param message 邮件消息模型
     * @return 包含发送结果回执的异步 CompletableFuture
     */
    public CompletableFuture<MailSendResult> sendAsyncWithAccount(MailAccount account, MailMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("MailMessage must not be null");
        }
        try {
            if (account == null) {
                throw new IllegalArgumentException("MailAccount must not be null");
            }
            Executor asyncExecutor = requireAsyncExecutor();
            return CompletableFuture.supplyAsync(AsyncContext.wrapSupplier(() -> sendWithAccount(account, message)), asyncExecutor);
        } catch (Throwable t) {
            message.close();
            throw t;
        }
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
        return sendTemplateAsync(null, to != null ? List.of(to) : List.of(), subject, templateName, model, null);
    }

    public CompletableFuture<MailSendResult> sendTemplateAsync(String to, String subject, String templateName, Map<String, Object> model, Locale locale) {
        return sendTemplateAsync(null, to != null ? List.of(to) : List.of(), subject, templateName, model, locale);
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
        return sendTemplateAsync(accountName, to != null ? List.of(to) : List.of(), subject, templateName, model, null);
    }

    public CompletableFuture<MailSendResult> sendTemplateAsync(String accountName, String to, String subject, String templateName, Map<String, Object> model, Locale locale) {
        return sendTemplateAsync(accountName, to != null ? List.of(to) : List.of(), subject, templateName, model, locale);
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
        return sendTemplateAsync(null, to, subject, templateName, model, null);
    }

    public CompletableFuture<MailSendResult> sendTemplateAsync(Collection<String> to, String subject, String templateName, Map<String, Object> model, Locale locale) {
        return sendTemplateAsync(null, to, subject, templateName, model, locale);
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
        return sendTemplateAsync(accountName, to, subject, templateName, model, null);
    }

    public CompletableFuture<MailSendResult> sendTemplateAsync(String accountName, Collection<String> to, String subject, String templateName, Map<String, Object> model, Locale locale) {
        String renderedHtml = renderTemplate(templateName, model, locale);
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
            try {
                registerAfterCommitSynchronization(message, () -> deliverAfterCommit(null, message), false);
            } catch (Throwable t) {
                message.close();
                throw t;
            }
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
        if (message == null) {
            throw new IllegalArgumentException("MailMessage must not be null");
        }
        if (account == null) {
            message.close();
            throw new IllegalArgumentException("MailAccount must not be null");
        }
        if (isTxSynchronizationActive()) {
            try {
                registerAfterCommitSynchronization(message, () -> deliverAfterCommit(account, message), false);
            } catch (Throwable t) {
                message.close();
                throw t;
            }
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
        withConfiguredMessage(messageConfigurer, msg -> {
            sendAfterCommit(msg);
            return null;
        });
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
            try {
                registerAfterCommitSynchronization(message, () -> deliverAfterCommit(null, message), true);
            } catch (Throwable t) {
                message.close();
                throw t;
            }
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
        if (message == null) {
            throw new IllegalArgumentException("MailMessage must not be null");
        }
        if (account == null) {
            message.close();
            throw new IllegalArgumentException("MailAccount must not be null");
        }
        if (isTxSynchronizationActive()) {
            try {
                registerAfterCommitSynchronization(message, () -> deliverAfterCommit(account, message), true);
            } catch (Throwable t) {
                message.close();
                throw t;
            }
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
        withConfiguredMessage(messageConfigurer, msg -> {
            sendAsyncAfterCommit(msg);
            return null;
        });
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
        sendTemplateAfterCommit(null, to != null ? List.of(to) : List.of(), subject, templateName, model, null);
    }

    public void sendTemplateAfterCommit(String to, String subject, String templateName, Map<String, Object> model, Locale locale) {
        sendTemplateAfterCommit(null, to != null ? List.of(to) : List.of(), subject, templateName, model, locale);
    }

    public void sendTemplateAfterCommit(String accountName, String to, String subject, String templateName, Map<String, Object> model) {
        sendTemplateAfterCommit(accountName, to != null ? List.of(to) : List.of(), subject, templateName, model, null);
    }

    public void sendTemplateAfterCommit(String accountName, String to, String subject, String templateName, Map<String, Object> model, Locale locale) {
        sendTemplateAfterCommit(accountName, to != null ? List.of(to) : List.of(), subject, templateName, model, locale);
    }

    public void sendTemplateAfterCommit(Collection<String> to, String subject, String templateName, Map<String, Object> model) {
        sendTemplateAfterCommit(null, to, subject, templateName, model, null);
    }

    public void sendTemplateAfterCommit(Collection<String> to, String subject, String templateName, Map<String, Object> model, Locale locale) {
        sendTemplateAfterCommit(null, to, subject, templateName, model, locale);
    }

    public void sendTemplateAfterCommit(String accountName, Collection<String> to, String subject, String templateName, Map<String, Object> model) {
        sendTemplateAfterCommit(accountName, to, subject, templateName, model, null);
    }

    public void sendTemplateAfterCommit(String accountName, Collection<String> to, String subject, String templateName, Map<String, Object> model, Locale locale) {
        String renderedHtml = renderTemplate(templateName, model, locale);
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
        sendTemplateAsyncAfterCommit(null, to != null ? List.of(to) : List.of(), subject, templateName, model, null);
    }

    public void sendTemplateAsyncAfterCommit(String to, String subject, String templateName, Map<String, Object> model, Locale locale) {
        sendTemplateAsyncAfterCommit(null, to != null ? List.of(to) : List.of(), subject, templateName, model, locale);
    }

    public void sendTemplateAsyncAfterCommit(String accountName, String to, String subject, String templateName, Map<String, Object> model) {
        sendTemplateAsyncAfterCommit(accountName, to != null ? List.of(to) : List.of(), subject, templateName, model, null);
    }

    public void sendTemplateAsyncAfterCommit(String accountName, String to, String subject, String templateName, Map<String, Object> model, Locale locale) {
        sendTemplateAsyncAfterCommit(accountName, to != null ? List.of(to) : List.of(), subject, templateName, model, locale);
    }

    public void sendTemplateAsyncAfterCommit(Collection<String> to, String subject, String templateName, Map<String, Object> model) {
        sendTemplateAsyncAfterCommit(null, to, subject, templateName, model, null);
    }

    public void sendTemplateAsyncAfterCommit(Collection<String> to, String subject, String templateName, Map<String, Object> model, Locale locale) {
        sendTemplateAsyncAfterCommit(null, to, subject, templateName, model, locale);
    }

    public void sendTemplateAsyncAfterCommit(String accountName, Collection<String> to, String subject, String templateName, Map<String, Object> model) {
        sendTemplateAsyncAfterCommit(accountName, to, subject, templateName, model, null);
    }

    public void sendTemplateAsyncAfterCommit(String accountName, Collection<String> to, String subject, String templateName, Map<String, Object> model, Locale locale) {
        String renderedHtml = renderTemplate(templateName, model, locale);
        sendHtmlAsyncAfterCommit(accountName, to, subject, renderedHtml);
    }

    // ==================== 物理 SMTP 连通性健康探针 ====================

    /**
     * 探测当前默认发信账号的物理 SMTP 连通性（快速布尔型探测）
     *
     * @return 连通成功返回 true，失败返回 false
     */
    public boolean testConnection() {
        return testConnection((String) null);
    }

    /**
     * 探测指定发信账号的物理 SMTP 连通性（快速布尔型探测）
     *
     * @param accountName 账号逻辑名称（传 null 时自动解析默认或租户账号）
     * @return 连通成功返回 true，失败返回 false
     */
    public boolean testConnection(String accountName) {
        try {
            testConnectionOrThrow(accountName);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 探测指定发件账号实体的物理 SMTP 连通性（快速布尔型探测）
     *
     * @param account 待检测发件账号
     * @return 连通成功返回 true，失败返回 false
     */
    public boolean testConnection(MailAccount account) {
        try {
            testConnectionOrThrow(account);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 探测当前默认发信账号的物理 SMTP 连通性（异常型探测，失败抛出语义化业务异常并保留因果链）
     *
     * @throws MailSendException 连通失败或认证异常时抛出
     */
    public void testConnectionOrThrow() {
        testConnectionOrThrow((String) null);
    }

    /**
     * 探测指定发信账号的物理 SMTP 连通性（异常型探测，失败抛出语义化业务异常并保留因果链）
     *
     * @param accountName 账号逻辑名称（传 null 时自动解析默认或租户账号）
     * @throws MailSendException 连通失败或认证异常时抛出
     */
    public void testConnectionOrThrow(String accountName) {
        MailAccount account;
        if (accountName != null && !accountName.isBlank()) {
            account = findAccount(accountName);
            if (account == null) {
                throw new MailSendException(MailResultCode.MAIL_ACCOUNT_NOT_FOUND,
                        "未找到指定的发信账号: [" + accountName + "]");
            }
        } else {
            account = resolveAccount(new MailMessage());
        }
        if (account == null) {
            throw new MailSendException(MailResultCode.MAIL_ACCOUNT_NOT_FOUND, "未找到可用的默认发信账号，请检查配置");
        }
        testConnectionOrThrow(account);
    }

    /**
     * 探测指定发件账号实体的物理 SMTP 连通性（异常型探测，失败抛出语义化业务异常并保留因果链）
     *
     * @param account 待检测发信账号
     * @throws MailSendException 连通失败或认证异常时抛出
     */
    public void testConnectionOrThrow(MailAccount account) {
        if (account == null) {
            throw new IllegalArgumentException("MailAccount must not be null");
        }
        accountRegistry.testConnectionOrThrow(account);
    }

    // ==================== 内部防御性校验与辅助逻辑 ====================

    /**
     * 校验邮件附件与总体积是否超出阈值限制，实施 Fail-Fast 防爆熔断
     *
     * @param message 待发送邮件消息
     */
    /**
     * 合规审计归档密送安全追加（支持大小写无关去重合并）
     *
     * @param account 目标发信账号
     * @param message 待发信消息模型
     */
    private void applyArchiveBcc(MailAccount account, MailMessage message) {
        String archiveBcc = (account != null && account.getArchiveBcc() != null && !account.getArchiveBcc().isBlank())
                ? account.getArchiveBcc()
                : (properties != null ? properties.getArchiveBcc() : null);

        if (archiveBcc == null || archiveBcc.isBlank()) {
            return;
        }

        String[] addresses = archiveBcc.split("[,;]");
        if (addresses.length == 0) {
            return;
        }

        List<String> mutableBcc = message.getBcc() != null ? new ArrayList<>(message.getBcc()) : new ArrayList<>();
        Set<String> existing = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        existing.addAll(mutableBcc);

        boolean modified = false;
        for (String address : addresses) {
            if (address == null) {
                continue;
            }
            String trimmed = address.trim();
            if (!trimmed.isBlank() && !existing.contains(trimmed)) {
                mutableBcc.add(trimmed);
                existing.add(trimmed);
                modified = true;
                log.debug("Appended compliance archive-bcc: [{}] to mail: [{}]",
                        DesensitizeUtil.email(trimmed), message.getSubject());
            }
        }

        if (modified || message.getBcc() == null) {
            message.setBcc(mutableBcc);
        }
    }

    private void enforceSizeLimits(MailMessage message) {
        long maxAttachmentBytes = (properties.getMaxAttachmentSize() != null)
                ? properties.getMaxAttachmentSize().toBytes()
                : Long.MAX_VALUE;
        long maxTotalBytes = (properties.getMaxTotalSize() != null)
                ? properties.getMaxTotalSize().toBytes()
                : Long.MAX_VALUE;

        // 1. 单附件与内联资源大小检查
        if (message.hasAttachments()) {
            for (MailAttachment attachment : message.getAttachments()) {
                if (attachment == null || attachment.isSystemJunk() || FileUtil.isSystemJunkFile(attachment.getFilename())) {
                    continue;
                }
                if (attachment.getSize() != null && attachment.getSize() > maxAttachmentBytes) {
                    throw new MailSendException(MailResultCode.MAIL_ATTACHMENT_SIZE_EXCEEDED,
                            "邮件附件 [" + attachment.getFilename() + "] 大小 (" + attachment.getSize()
                                    + " 字节) 超出单个附件上限 (" + properties.getMaxAttachmentSize() + ")");
                }
            }
        }
        if (message.hasInlines()) {
            for (MailInlineResource inline : message.getInlines()) {
                if (inline == null || inline.isSystemJunk()) {
                    continue;
                }
                if (inline.getSize() != null && inline.getSize() > maxAttachmentBytes) {
                    throw new MailSendException(MailResultCode.MAIL_ATTACHMENT_SIZE_EXCEEDED,
                            "邮件内联资源 [" + inline.getContentId() + "] 大小 (" + inline.getSize()
                                    + " 字节) 超出单个附件上限 (" + properties.getMaxAttachmentSize() + ")");
                }
            }
        }

        // 2. 邮件已知总容量检查
        long estimatedTotal = computeKnownTotalBytes(message);

        if (estimatedTotal > maxTotalBytes) {
            throw new MailSendException(MailResultCode.MAIL_TOTAL_SIZE_EXCEEDED,
                    "邮件总容量预估 (" + estimatedTotal + " 字节) 超出总体积上限 (" + properties.getMaxTotalSize() + ")");
        }
    }

    private long computeKnownTotalBytes(MailMessage message) {
        long estimatedTotal = (message.getText() != null)
                ? message.getText().getBytes(StandardCharsets.UTF_8).length
                : 0L;

        if (message.hasAttachments()) {
            for (MailAttachment attachment : message.getAttachments()) {
                if (attachment != null && !attachment.isSystemJunk()
                        && !FileUtil.isSystemJunkFile(attachment.getFilename())
                        && attachment.getSize() != null) {
                    estimatedTotal += attachment.getSize();
                }
            }
        }

        if (message.hasInlines()) {
            for (MailInlineResource inline : message.getInlines()) {
                if (inline != null && !inline.isSystemJunk() && inline.getSize() != null) {
                    estimatedTotal += inline.getSize();
                }
            }
        }
        return estimatedTotal;
    }

    private String resolveTraceId() {
        String traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            return MailMessage.sanitizeHeaderText(traceId);
        }
        return IdUtil.fastSimpleUUID();
    }

    private String resolveDomain(MailAccount account) {
        String domain = account != null ? account.getDomain() : "ateng-boot.local";
        return MailMessage.sanitizeHeaderText(domain);
    }

    private void sanitizeMessageHeaders(MailMessage message) {
        if (message.getSubject() != null) {
            message.setSubject(message.getSubject());
        }
        if (message.getFromName() != null) {
            message.setFromName(message.getFromName());
        }
        if (message.getHeaders() != null && !message.getHeaders().isEmpty()) {
            message.setHeaders(message.getHeaders());
        }
        if (message.hasInlines()) {
            for (MailInlineResource inline : message.getInlines()) {
                if (inline != null && inline.getContentId() != null) {
                    inline.setContentId(MailInlineResource.cleanContentId(inline.getContentId()));
                }
            }
        }
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

    private boolean isRepeatableMessage(MailMessage message) {
        if (message.hasAttachments()) {
            for (MailAttachment attachment : message.getAttachments()) {
                if (attachment != null && !attachment.isSystemJunk() && !attachment.isRepeatable()) {
                    return false;
                }
            }
        }
        if (message.hasInlines()) {
            for (MailInlineResource inline : message.getInlines()) {
                if (inline != null && !inline.isSystemJunk() && !inline.isRepeatable()) {
                    return false;
                }
            }
        }
        return true;
    }

    private void closeAllMessagesQuietly(List<MailMessage> messages) {
        if (messages == null) {
            return;
        }
        for (MailMessage msg : messages) {
            if (msg != null) {
                msg.close();
            }
        }
    }

    private <T> T withConfiguredMessage(Consumer<MailMessage> messageConfigurer, Function<MailMessage, T> action) {
        if (messageConfigurer == null) {
            throw new IllegalArgumentException("messageConfigurer must not be null");
        }
        MailMessage message = new MailMessage();
        try {
            messageConfigurer.accept(message);
            return action.apply(message);
        } catch (Throwable t) {
            message.close();
            throw t;
        }
    }

    private boolean isRetryableException(Throwable throwable) {
        if (throwable instanceof MailAuthenticationException || throwable instanceof IllegalArgumentException) {
            return false;
        }
        if (throwable instanceof MailSendException mse) {
            if (mse.getResultCode() == MailResultCode.MAIL_AUTHENTICATION_ERROR
                    || mse.getResultCode() == MailResultCode.MAIL_ACCOUNT_NOT_FOUND
                    || mse.getResultCode() == MailResultCode.MAIL_RECIPIENT_EMPTY
                    || mse.getResultCode() == MailResultCode.MAIL_INVALID_ADDRESS
                    || mse.getResultCode() == MailResultCode.MAIL_ATTACHMENT_SIZE_EXCEEDED
                    || mse.getResultCode() == MailResultCode.MAIL_TOTAL_SIZE_EXCEEDED
                    || mse.getResultCode() == MailResultCode.MAIL_TEMPLATE_RENDER_ERROR
                    || (mse.getMessage() != null && mse.getMessage().contains("邮件发送被拦截器阻断"))) {
                return false;
            }
        }
        if (containsExceptionType(throwable, AddressException.class)) {
            return false;
        }
        return true;
    }

    private void validateMessageAddresses(MailAccount account, MailMessage message) {
        validateRecipientAddresses(message);
        if (message.getFrom() != null && !message.getFrom().isBlank()) {
            validateSingleEmailAddress(message.getFrom(), "from");
        }
        if (account != null && account.getDefaultFrom() != null && !account.getDefaultFrom().isBlank()) {
            validateSingleEmailAddress(account.getDefaultFrom(), "defaultFrom");
        }
        if (message.getReplyTo() != null && !message.getReplyTo().isBlank()) {
            validateSingleEmailAddress(message.getReplyTo(), "replyTo");
        }
        if (message.getReadReceiptTo() != null && !message.getReadReceiptTo().isBlank()) {
            validateSingleEmailAddress(message.getReadReceiptTo(), "readReceiptTo");
        }
        if (account != null) {
            String archiveBcc = (account.getArchiveBcc() != null && !account.getArchiveBcc().isBlank())
                    ? account.getArchiveBcc()
                    : (properties != null ? properties.getArchiveBcc() : null);
            if (archiveBcc != null && !archiveBcc.isBlank()) {
                for (String addr : archiveBcc.split("[,;]")) {
                    if (addr != null && !addr.isBlank()) {
                        validateSingleEmailAddress(addr, "archiveBcc");
                    }
                }
            }
        }
    }

    private void validateRecipientAddresses(MailMessage message) {
        if (message.getTo() != null) {
            for (String addr : message.getTo()) {
                validateSingleEmailAddress(addr, "to");
            }
        }
        if (message.getCc() != null) {
            for (String addr : message.getCc()) {
                validateSingleEmailAddress(addr, "cc");
            }
        }
        if (message.getBcc() != null) {
            for (String addr : message.getBcc()) {
                validateSingleEmailAddress(addr, "bcc");
            }
        }
    }

    private static void validateSingleEmailAddress(String rawAddress, String fieldName) {
        if (rawAddress == null || rawAddress.isBlank()) {
            throw new MailSendException(MailResultCode.MAIL_INVALID_ADDRESS,
                    fieldName + " 邮件地址不能为空或空白");
        }
        if (rawAddress.indexOf('\r') >= 0 || rawAddress.indexOf('\n') >= 0 || rawAddress.indexOf('\0') >= 0) {
            throw new MailSendException(MailResultCode.MAIL_INVALID_ADDRESS,
                    fieldName + " 邮件地址包含非法换行或空字符");
        }
        String trimmed = rawAddress.trim();
        try {
            InternetAddress[] parsed = InternetAddress.parse(trimmed, true);
            if (parsed == null || parsed.length != 1) {
                throw new MailSendException(MailResultCode.MAIL_INVALID_ADDRESS,
                        fieldName + " 邮件地址格式非法: [" + rawAddress + "]");
            }
            parsed[0].validate();
            String addr = parsed[0].getAddress();
            if (addr == null || addr.isBlank()) {
                throw new MailSendException(MailResultCode.MAIL_INVALID_ADDRESS,
                        fieldName + " 邮件地址格式非法: [" + rawAddress + "]");
            }
            int atIdx = addr.indexOf('@');
            if (atIdx <= 0 || atIdx >= addr.length() - 1 || addr.indexOf('@', atIdx + 1) >= 0) {
                throw new MailSendException(MailResultCode.MAIL_INVALID_ADDRESS,
                        fieldName + " 邮件地址格式非法: [" + rawAddress + "]");
            }
            String domain = addr.substring(atIdx + 1);
            if (domain.startsWith(".") || domain.endsWith(".") || domain.contains("..")) {
                throw new MailSendException(MailResultCode.MAIL_INVALID_ADDRESS,
                        fieldName + " 邮件域名格式非法: [" + rawAddress + "]");
            }
        } catch (AddressException e) {
            throw new MailSendException(MailResultCode.MAIL_INVALID_ADDRESS,
                    fieldName + " 邮件地址格式非法: [" + rawAddress + "]", e);
        }
    }

    private static InternetAddress parseSingleInternetAddress(String rawAddress) {
        try {
            InternetAddress[] parsed = InternetAddress.parse(rawAddress.trim(), true);
            return (parsed != null && parsed.length == 1) ? parsed[0] : null;
        } catch (AddressException ignored) {
            return null;
        }
    }

    private static boolean containsExceptionType(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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

    private boolean isSandboxLogOnly(MailMessage message) {
        if (properties.getSandbox() != null && properties.getSandbox().isEnabled() && properties.getSandbox().isLogOnly()) {
            return true;
        }
        return message.getHeaders() != null && "true".equalsIgnoreCase(message.getHeaders().get(MailSandboxInterceptor.HEADER_SANDBOX_LOG_ONLY));
    }

    private Executor requireAsyncExecutor() {
        if (this.executor == null) {
            throw new MailSendException(MailResultCode.MAIL_SEND_ERROR,
                    "邮件异步执行器未启用或未装配 (ateng.boot.mail.async.enabled=false)");
        }
        return this.executor;
    }

    private void registerAfterCommitSynchronization(MailMessage message, Runnable deliveryTask, boolean isAsync) {
        Executor asyncExecutor = isAsync ? requireAsyncExecutor() : null;
        AsyncContext.ContextSnapshot parentSnapshot = AsyncContext.ContextSnapshot.capture();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (isAsync) {
                    try {
                        asyncExecutor.execute(() -> executeDeliveryWithContext(message, parentSnapshot, deliveryTask));
                    } catch (Throwable submitEx) {
                        // triggerFailureSafetyNet 内部已打印 [MailSafetyNet] 错误日志并触发告警事件与回调
                        try {
                            triggerFailureSafetyNet(message, maskRecipients(message), submitEx);
                        } finally {
                            message.close();
                        }
                    }
                } else {
                    executeDeliveryWithContext(message, parentSnapshot, deliveryTask);
                }
            }

            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    try {
                        String masked = maskRecipients(message);
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

    private void deliverAfterCommit(MailAccount explicitAccount, MailMessage message) {
        MailAccount targetAccount;
        try {
            requireNonEmptyRecipients(message);
            targetAccount = explicitAccount != null ? explicitAccount : resolveAccount(message);
        } catch (Throwable preEx) {
            try {
                triggerFailureSafetyNet(message, maskRecipients(message), preEx);
            } finally {
                message.close();
            }
            return;
        }
        try {
            sendWithAccount(targetAccount, message);
        } catch (Throwable deliveryEx) {
            // 事务提交后投递失败已由 sendWithAccount 触发失败兜底网并关闭资源，此处消费异常防止外层重复打印堆栈
        }
    }

    private String maskRecipients(MailMessage message) {
        if (message == null || message.getTo() == null) {
            return "";
        }
        return message.getTo().stream()
                .filter(Objects::nonNull)
                .map(DesensitizeUtil::email)
                .collect(Collectors.joining(","));
    }

    private void executeDeliveryWithContext(MailMessage message, AsyncContext.ContextSnapshot parentSnapshot, Runnable deliveryTask) {
        AsyncContext.ContextSnapshot previous = AsyncContext.ContextSnapshot.capturePrevious();
        try {
            parentSnapshot.apply();
            deliveryTask.run();
        } catch (Throwable t) {
            log.error("[MailAfterCommit] Delivery after commit failed for subject [{}]: {}",
                    message != null ? message.getSubject() : null, t.getMessage(), t);
        } finally {
            previous.restore();
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

    private static ThreadPoolTaskExecutor createDefaultExecutor(AsyncPoolProperties pool) {
        ThreadPoolTaskExecutor executor = AsyncExecutorFactory.createPlatformExecutor("mail", pool);
        executor.initialize();
        return executor;
    }

    private InputStreamSource wrapBoundedSource(String filename, InputStreamSource originalSource, long maxBytes,
                                                CumulativeSizeTracker sizeTracker,
                                                AtomicReference<MailSendException> sizeLimitExceptionRef) {
        AtomicLong itemMaxBytesRead = new AtomicLong(0);
        return () -> {
            InputStream in = originalSource.getInputStream();
            return new BoundedInputStream(in, maxBytes, filename, itemMaxBytesRead, sizeTracker, sizeLimitExceptionRef);
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
     * 邮件流式未知大小累加总容量跟踪器
     */
    private final class CumulativeSizeTracker {
        private final long maxTotalBytes;
        private final AtomicLong totalBytes;
        private final AtomicReference<MailSendException> sizeLimitExceptionRef;

        private CumulativeSizeTracker(long initialKnownBytes, long maxTotalBytes,
                                      AtomicReference<MailSendException> sizeLimitExceptionRef) {
            this.maxTotalBytes = maxTotalBytes;
            this.totalBytes = new AtomicLong(initialKnownBytes);
            this.sizeLimitExceptionRef = sizeLimitExceptionRef;
        }

        private void recordItemProgress(AtomicLong itemMaxRead, long currentItemBytesRead, String itemName) throws IOException {
            long prevMax = itemMaxRead.get();
            if (currentItemBytesRead <= prevMax) {
                return;
            }
            while (!itemMaxRead.compareAndSet(prevMax, currentItemBytesRead)) {
                prevMax = itemMaxRead.get();
                if (currentItemBytesRead <= prevMax) {
                    return;
                }
            }
            long delta = currentItemBytesRead - prevMax;
            long accumulated = totalBytes.addAndGet(delta);
            if (accumulated > maxTotalBytes) {
                MailSendException ex = new MailSendException(MailResultCode.MAIL_TOTAL_SIZE_EXCEEDED,
                        "邮件流式读取累加总体积 (" + accumulated + " 字节) 超出总体积上限 (" + properties.getMaxTotalSize() + ")，触发资源: [" + itemName + "]");
                if (sizeLimitExceptionRef != null) {
                    sizeLimitExceptionRef.compareAndSet(null, ex);
                }
                throw ex;
            }
        }
    }

    /**
     * 流式附件与内联资源读取容量上限守护流，严密防范未知容量裸流耗尽物理内存
     */
    private static class BoundedInputStream extends FilterInputStream {
        private final long maxBytes;
        private final String filename;
        private final AtomicLong itemMaxBytesRead;
        private final CumulativeSizeTracker sizeTracker;
        private final AtomicReference<MailSendException> sizeLimitExceptionRef;
        private long bytesRead = 0;

        public BoundedInputStream(InputStream in, long maxBytes, String filename,
                                  AtomicLong itemMaxBytesRead, CumulativeSizeTracker sizeTracker,
                                  AtomicReference<MailSendException> sizeLimitExceptionRef) {
            super(in);
            this.maxBytes = maxBytes;
            this.filename = filename;
            this.itemMaxBytesRead = itemMaxBytesRead;
            this.sizeTracker = sizeTracker;
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
            if (sizeTracker != null) {
                sizeTracker.recordItemProgress(itemMaxBytesRead, bytesRead, filename);
            }
        }
    }
}
