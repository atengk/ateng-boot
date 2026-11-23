package io.github.atengk.boot.sample.mail.service;

import freemarker.core.HTMLOutputFormat;
import freemarker.template.Configuration;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.mail.exception.MailSendException;
import io.github.atengk.boot.mail.guard.InMemoryMailDailyQuotaBreaker;
import io.github.atengk.boot.mail.guard.InMemoryMailIdempotentGuard;
import io.github.atengk.boot.mail.guard.InMemoryMailRateLimiter;
import io.github.atengk.boot.mail.guard.MailDailyQuotaBreaker;
import io.github.atengk.boot.mail.guard.MailIdempotentGuard;
import io.github.atengk.boot.mail.guard.MailRateLimiter;
import io.github.atengk.boot.mail.interceptor.MailSandboxInterceptor;
import io.github.atengk.boot.mail.interceptor.MailSendInterceptor;
import io.github.atengk.boot.mail.metrics.MailMetrics;
import io.github.atengk.boot.mail.model.AuthMode;
import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.model.MailMessage;
import io.github.atengk.boot.mail.model.MailSendResult;
import io.github.atengk.boot.mail.properties.MailIdempotentProperties;
import io.github.atengk.boot.mail.properties.MailProperties;
import io.github.atengk.boot.mail.properties.MailQuotaProperties;
import io.github.atengk.boot.mail.properties.MailRateLimitProperties;
import io.github.atengk.boot.mail.properties.MailSandboxProperties;
import io.github.atengk.boot.mail.registry.MailAccountRegistry;
import io.github.atengk.boot.mail.template.MailTemplate;
import io.github.atengk.boot.mail.template.renderer.FreemarkerMailTemplateRenderer;
import io.github.atengk.boot.observability.health.MailSafeHealthIndicator;
import io.github.atengk.boot.sample.mail.dto.BatchAfterCommitRequest;
import io.github.atengk.boot.sample.mail.dto.ComplianceFreemarkerRequest;
import io.github.atengk.boot.sample.mail.dto.FailoverDemoRequest;
import io.github.atengk.boot.sample.mail.dto.GuardDemoResponse;
import io.github.atengk.boot.sample.mail.dto.ObservabilitySnapshotResponse;
import io.github.atengk.boot.sample.mail.dto.OutboundGuardDemoRequest;
import io.github.atengk.boot.sample.mail.dto.SandboxDemoRequest;
import io.github.atengk.boot.sample.mail.dto.SimpleTextMailRequest;
import io.github.atengk.boot.sample.mail.dto.TenantRouteMailRequest;
import io.github.atengk.boot.sample.mail.listener.SampleMailEventListener;
import io.github.atengk.boot.sample.mail.provider.SampleOAuth2TokenProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 邮件高阶治理、容灾、合规、沙箱与可观测场景演示服务
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Service
@RequiredArgsConstructor
public class MailGovernanceService {

    private static final Logger log = LoggerFactory.getLogger(MailGovernanceService.class);
    private static final FreemarkerMailTemplateRenderer FREEMARKER_RENDERER = createFreemarkerRenderer();

    private final MailTemplate mailTemplate;
    private final MailProperties mailProperties;
    private final SampleMailEventListener eventListener;
    private final SampleOAuth2TokenProvider oauth2TokenProvider;
    private final ObjectProvider<MailSafeHealthIndicator> mailHealthIndicatorProvider;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    /**
     * 演示账号令牌桶频控触发与恢复
     *
     * @param request 演示请求
     * @return 治理演示响应
     */
    public GuardDemoResponse runRateLimitDemo(OutboundGuardDemoRequest request) {
        log.info("Running rate-limit demo for recipient [{}], burstCount={}",
                DesensitizeUtil.email(request.getTo()), request.getBurstCount());

        MailRateLimitProperties props = new MailRateLimitProperties();
        props.setEnabled(true);
        props.setAccountCapacity(1L);
        props.setAccountPermitsPerSecond(0.05d);
        props.setRecipientMaxCount(10L);

        int allowed = 0;
        int blocked = 0;
        Integer triggeredCode = null;
        List<String> messageIds = new ArrayList<>();
        boolean recovered = false;

        MailTemplate guardedTemplate = buildGuardedTemplate(
                new InMemoryMailRateLimiter(props), null, null, null, null);
        try {
            for (int i = 0; i < request.getBurstCount(); i++) {
                try {
                    MailSendResult res = guardedTemplate.send(new MailMessage()
                            .to(request.getTo())
                            .subject(request.getSubject() + " #" + (i + 1))
                            .text(request.getContent()));
                    allowed++;
                    messageIds.add(res.getMessageId());
                } catch (MailSendException mse) {
                    blocked++;
                    triggeredCode = mse.getResultCode().getCode();
                }
            }
        } finally {
            guardedTemplate.destroy();
        }

        // 模拟进入新限流窗口（重建限流器）验证恢复放行
        MailTemplate recoveredTemplate = buildGuardedTemplate(
                new InMemoryMailRateLimiter(props), null, null, null, null);
        try {
            MailSendResult recoveredRes = recoveredTemplate.send(new MailMessage()
                    .to(request.getTo())
                    .subject(request.getSubject() + " [Recovered]")
                    .text(request.getContent()));
            recovered = recoveredRes.isSuccess();
            messageIds.add(recoveredRes.getMessageId());
        } catch (Exception ex) {
            log.warn("Rate-limit recovery check failed: {}", ex.getMessage());
        } finally {
            recoveredTemplate.destroy();
        }

        return GuardDemoResponse.builder()
                .allowedCount(allowed)
                .blockedCount(blocked)
                .triggeredResultCode(triggeredCode)
                .recoveredAfterResetOrRelease(recovered)
                .messageIds(messageIds)
                .summary("频控演示完成：放行 " + allowed + " 次，拦截 " + blocked + " 次，重置窗口恢复=" + recovered)
                .build();
    }

    /**
     * 演示自然日总配额硬熔断与 MailQuotaExceededEvent 领域事件捕获
     *
     * @param request 演示请求
     * @return 治理演示响应
     */
    public GuardDemoResponse runQuotaDemo(OutboundGuardDemoRequest request) {
        log.info("Running daily quota breaker demo for recipient [{}], burstCount={}",
                DesensitizeUtil.email(request.getTo()), request.getBurstCount());

        int beforeEvents = eventListener.getQuotaExceededCount().get();
        MailQuotaProperties props = new MailQuotaProperties();
        props.setEnabled(true);
        props.setDailyMaxCount(1L);

        int allowed = 0;
        int blocked = 0;
        Integer triggeredCode = null;
        List<String> messageIds = new ArrayList<>();

        MailTemplate guardedTemplate = buildGuardedTemplate(
                null, new InMemoryMailDailyQuotaBreaker(props), null, null, null);
        try {
            for (int i = 0; i < request.getBurstCount(); i++) {
                try {
                    MailSendResult res = guardedTemplate.send(new MailMessage()
                            .to(request.getTo())
                            .subject(request.getSubject() + " #" + (i + 1))
                            .text(request.getContent()));
                    allowed++;
                    messageIds.add(res.getMessageId());
                } catch (MailSendException mse) {
                    blocked++;
                    triggeredCode = mse.getResultCode().getCode();
                }
            }
        } finally {
            guardedTemplate.destroy();
        }

        boolean eventCaptured = eventListener.getQuotaExceededCount().get() > beforeEvents;
        return GuardDemoResponse.builder()
                .allowedCount(allowed)
                .blockedCount(blocked)
                .triggeredResultCode(triggeredCode)
                .quotaEventCaptured(eventCaptured)
                .messageIds(messageIds)
                .summary("日配额熔断演示完成：放行 " + allowed + " 次，拦截 " + blocked + " 次，事件捕获=" + eventCaptured)
                .build();
    }

    /**
     * 演示 bizId 出站幂等防重与失败自动放锁可重试语义
     *
     * @param request 演示请求
     * @return 治理演示响应
     */
    public GuardDemoResponse runIdempotentDemo(OutboundGuardDemoRequest request) {
        String bizId = (request.getBizId() != null && !request.getBizId().isBlank())
                ? request.getBizId().trim()
                : "BIZ-" + System.currentTimeMillis();
        log.info("Running idempotent guard demo for recipient [{}], bizId=[{}]",
                DesensitizeUtil.email(request.getTo()), bizId);

        MailIdempotentProperties props = new MailIdempotentProperties();
        props.setEnabled(true);
        props.setRequireBizId(true);
        props.setTtl(Duration.ofMinutes(5));

        int allowed = 0;
        int blocked = 0;
        Integer triggeredCode = null;
        List<String> messageIds = new ArrayList<>();
        boolean failedAsExpected = false;
        boolean recoveredAfterFailure = false;

        MailTemplate guardedTemplate = buildGuardedTemplate(
                null, null, new InMemoryMailIdempotentGuard(props), null, null);
        try {
            // 1. 同 bizId 连续提交两次：首次放行，二次触发 MAIL_IDEMPOTENT_REJECTED
            for (int i = 0; i < 2; i++) {
                try {
                    MailSendResult res = guardedTemplate.send(new MailMessage()
                            .bizId(bizId)
                            .to(request.getTo())
                            .subject(request.getSubject())
                            .text(request.getContent()));
                    allowed++;
                    messageIds.add(res.getMessageId());
                } catch (MailSendException mse) {
                    blocked++;
                    triggeredCode = mse.getResultCode().getCode();
                }
            }

            // 2. 验证失败自动放锁：使用新 bizId 通过不可达账号触发失败，随后同 bizId 改走健康通道重试成功
            String retryBizId = bizId + "-retry";
            MailAccount brokenAccount = buildUnreachablePrimaryAccount(Collections.emptyList(), null);
            try {
                guardedTemplate.sendWithAccount(brokenAccount, new MailMessage()
                        .bizId(retryBizId)
                        .to(request.getTo())
                        .subject(request.getSubject() + " [Fail]")
                        .text(request.getContent()));
            } catch (MailSendException ex) {
                failedAsExpected = true;
                log.debug("Expected failure on broken account released idempotent lock for bizId [{}]: {}",
                        retryBizId, ex.getMessage());
            }
            if (failedAsExpected) {
                try {
                    MailSendResult retryRes = guardedTemplate.send(new MailMessage()
                            .bizId(retryBizId)
                            .to(request.getTo())
                            .subject(request.getSubject() + " [RetrySuccess]")
                            .text(request.getContent()));
                    recoveredAfterFailure = retryRes.isSuccess();
                    messageIds.add(retryRes.getMessageId());
                } catch (Exception ex) {
                    log.warn("Idempotent failure-release retry failed: {}", ex.getMessage());
                }
            }
        } finally {
            guardedTemplate.destroy();
        }

        return GuardDemoResponse.builder()
                .allowedCount(allowed)
                .blockedCount(blocked)
                .triggeredResultCode(triggeredCode)
                .recoveredAfterResetOrRelease(recoveredAfterFailure)
                .messageIds(messageIds)
                .summary("幂等防重演示完成：放行 " + allowed + " 次，拦截 " + blocked + " 次，失败放锁重试成功=" + recoveredAfterFailure)
                .build();
    }

    /**
     * 演示 backupAccounts 主备账号顺延容灾与 From 域自动对齐
     *
     * @param request 容灾演示请求
     * @return 备用账号成功投递回执
     */
    public MailSendResult runFailoverDemo(FailoverDemoRequest request) {
        String primaryFrom = (request.getPrimaryFrom() != null && !request.getPrimaryFrom().isBlank())
                ? request.getPrimaryFrom().trim()
                : "primary-gw@primary-corp.example.com";
        log.info("Running cascade failover demo to [{}], primaryFrom=[{}]",
                DesensitizeUtil.email(request.getTo()), DesensitizeUtil.email(primaryFrom));

        MailAccount primaryAccount = buildUnreachablePrimaryAccount(List.of("ops"), primaryFrom);
        return mailTemplate.forDynamicAccount(primaryAccount).send(message -> message
                .to(request.getTo())
                .subject(request.getSubject())
                .text(request.getContent()));
    }

    /**
     * 演示 sendBatchAfterCommit 事务提交后批量邮件合并（支持回滚防幽灵取消）
     *
     * @param request 批量事务请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void sendBatchAfterCommit(BatchAfterCommitRequest request) {
        log.info("Running sendBatchAfterCommit demo, size={}, shouldRollback={}",
                request.getItems().size(), request.isShouldRollback());

        List<MailMessage> messages = new ArrayList<>(request.getItems().size());
        for (SimpleTextMailRequest item : request.getItems()) {
            messages.add(new MailMessage()
                    .to(item.getTo())
                    .subject(item.getSubject())
                    .text(item.getContent()));
        }
        mailTemplate.sendBatchAfterCommit(messages);

        if (request.isShouldRollback()) {
            throw new IllegalStateException("Simulated business exception triggering batch rollback");
        }
    }

    /**
     * 演示沙箱双策略（白名单重定向清场 / 纯日志模拟）
     *
     * @param request 沙箱演示请求
     * @return 发信结果回执
     */
    public MailSendResult runSandboxDemo(SandboxDemoRequest request) {
        log.info("Running sandbox demo to [{}], logOnly={}, redirectTo=[{}]",
                DesensitizeUtil.email(request.getTo()), request.isLogOnly(), DesensitizeUtil.email(request.getRedirectTo()));

        MailSandboxProperties sandboxProps = new MailSandboxProperties();
        sandboxProps.setEnabled(true);
        sandboxProps.setLogOnly(request.isLogOnly());
        sandboxProps.setRedirectTo(request.getRedirectTo());
        sandboxProps.setAllowedRecipients(request.getAllowedRecipients() != null
                ? new ArrayList<>(request.getAllowedRecipients())
                : new ArrayList<>());

        List<MailSendInterceptor> sandboxInterceptors = new ArrayList<>();
        sandboxInterceptors.add(new MailSandboxInterceptor(sandboxProps));
        sandboxInterceptors.addAll(mailTemplate.getInterceptors());

        MailTemplate sandboxTemplate = buildGuardedTemplate(
                null, null, null, sandboxInterceptors, sandboxProps);
        try {
            MailMessage message = new MailMessage()
                    .to(request.getTo())
                    .subject(request.getSubject())
                    .text(request.getContent());
            if (request.getCc() != null && !request.getCc().isBlank()) {
                message.cc(request.getCc());
            }
            return sandboxTemplate.send(message);
        } finally {
            sandboxTemplate.destroy();
        }
    }

    /**
     * 演示 XOAUTH2 动态令牌首次过期 535 鉴权失败后自动刷新令牌并单次重试送达
     *
     * @param request 发信请求
     * @return 刷新令牌后成功投递回执
     */
    public MailSendResult runOAuth2RefreshDemo(SimpleTextMailRequest request) {
        log.info("Running XOAUTH2 dynamic token refresh demo to [{}]", DesensitizeUtil.email(request.getTo()));
        MailAccount defaultAccount = mailTemplate.getAccountRegistry().getDefault();
        JavaMailSenderImpl underlyingSender = defaultAccount.getSender();
        String accountName = "oauth2-demo";
        oauth2TokenProvider.seedInitialToken(accountName, "stale-oauth2-token");

        AtomicInteger attempts = new AtomicInteger(0);
        JavaMailSenderImpl oauthRefreshSender = new JavaMailSenderImpl() {
            @Override
            public MimeMessage createMimeMessage() {
                return underlyingSender.createMimeMessage();
            }

            @Override
            public void send(MimeMessage mimeMessage) {
                if (attempts.incrementAndGet() == 1) {
                    throw new MailAuthenticationException("535 5.7.8 XOAUTH2 access token expired");
                }
                underlyingSender.send(mimeMessage);
            }
        };

        MailAccount oauthAccount = MailAccount.builder()
                .name(accountName)
                .host(defaultAccount.getHost())
                .port(defaultAccount.getPort())
                .username(defaultAccount.getUsername())
                .authMode(AuthMode.XOAUTH2)
                .ssl(defaultAccount.isSsl())
                .starttls(defaultAccount.isStarttls())
                .defaultFrom(defaultAccount.getDefaultFrom())
                .defaultFromName(defaultAccount.getDefaultFromName())
                .sender(oauthRefreshSender)
                .build();

        return mailTemplate.forDynamicAccount(oauthAccount).send(message -> message
                .to(request.getTo())
                .subject(request.getSubject())
                .text(request.getContent()));
    }

    /**
     * 演示 Freemarker 模板渲染与单封 Return-Path / List-Unsubscribe 合规协议头
     *
     * @param request 合规模板发信请求
     * @return 发信结果回执
     */
    public MailSendResult sendFreemarkerCompliance(ComplianceFreemarkerRequest request) {
        log.info("Running Freemarker compliance mail demo to [{}], returnPath=[{}]",
                DesensitizeUtil.email(request.getTo()), DesensitizeUtil.email(request.getReturnPath()));

        String renderedHtml = FREEMARKER_RENDERER.render("mail/compliance-notice.ftl", Map.of(
                "username", request.getUsername(),
                "noticeTitle", request.getNoticeTitle()
        ));

        return mailTemplate.send(message -> message
                .to(request.getTo())
                .subject(request.getNoticeTitle())
                .html(renderedHtml)
                .returnPath(request.getReturnPath())
                .listUnsubscribe(request.getListUnsubscribe())
                .listUnsubscribePost(request.getListUnsubscribePost()));
    }

    /**
     * 演示 TenantContextHolder 多租户上下文动态路由发信（finally 确定性清理租户上下文）
     *
     * @param request 租户路由发信请求
     * @return 发信结果回执
     */
    public MailSendResult sendWithTenantContext(TenantRouteMailRequest request) {
        log.info("Running tenant context routing demo, tenantId=[{}], to=[{}]",
                request.getTenantId(), DesensitizeUtil.email(request.getTo()));
        TenantContextHolder.setTenantId(request.getTenantId());
        try {
            return mailTemplate.sendText(request.getTo(), request.getSubject(), request.getContent());
        } finally {
            TenantContextHolder.clear();
        }
    }

    /**
     * 演示 sendAsyncAfterCommit 事务提交后受管异步发信
     *
     * @param request        发信请求
     * @param shouldRollback 是否模拟业务异常回滚
     */
    @Transactional(rollbackFor = Exception.class)
    public void sendAsyncAfterCommit(SimpleTextMailRequest request, boolean shouldRollback) {
        log.info("Running sendAsyncAfterCommit demo to [{}], shouldRollback={}",
                DesensitizeUtil.email(request.getTo()), shouldRollback);
        mailTemplate.sendAsyncAfterCommit(new MailMessage()
                .to(request.getTo())
                .subject(request.getSubject())
                .text(request.getContent()));
        if (shouldRollback) {
            throw new IllegalStateException("Simulated business exception triggering async-after-commit rollback");
        }
    }

    /**
     * 演示函数式 DSL 极简发信调用
     *
     * @param request 发信请求
     * @return 发信结果回执
     */
    public MailSendResult sendViaDsl(SimpleTextMailRequest request) {
        log.info("Running functional DSL send demo to [{}]", DesensitizeUtil.email(request.getTo()));
        return mailTemplate.send(message -> message
                .to(request.getTo())
                .subject(request.getSubject())
                .text(request.getContent()));
    }

    /**
     * 获取邮件健康探针与 Micrometer 指标实时观测快照
     *
     * @return 可观测快照响应
     */
    public ObservabilitySnapshotResponse getObservabilitySnapshot() {
        MailSafeHealthIndicator indicator = mailHealthIndicatorProvider.getIfAvailable();
        Health health = indicator != null ? indicator.health() : Health.unknown().build();
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();

        double staticCount = readGauge(meterRegistry, MailMetrics.METRIC_ACCOUNTS_STATIC_SIZE);
        double dynamicCount = readGauge(meterRegistry, MailMetrics.METRIC_ACCOUNTS_DYNAMIC_SIZE);
        double totalSend = sumCounters(meterRegistry, MailMetrics.METRIC_SEND_COUNT, null);
        double successSend = sumCounters(meterRegistry, MailMetrics.METRIC_SEND_COUNT, "success");
        double sandboxSend = sumCounters(meterRegistry, MailMetrics.METRIC_SEND_COUNT, "sandbox");
        double retryCount = sumCounters(meterRegistry, MailMetrics.METRIC_RETRY_COUNT, null);
        double failoverCount = sumCounters(meterRegistry, MailMetrics.METRIC_FAILOVER_COUNT, null);

        return ObservabilitySnapshotResponse.builder()
                .healthStatus(health.getStatus().getCode())
                .healthDetails(health.getDetails())
                .staticAccountCount(staticCount)
                .dynamicAccountCount(dynamicCount)
                .totalSendCount(totalSend)
                .successCount(successSend)
                .sandboxCount(sandboxSend)
                .nonSuccessCount(Math.max(0.0, totalSend - successSend - sandboxSend))
                .retryCount(retryCount)
                .failoverCount(failoverCount)
                .build();
    }

    private static FreemarkerMailTemplateRenderer createFreemarkerRenderer() {
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_32);
        cfg.setClassForTemplateLoading(MailGovernanceService.class, "/templates");
        cfg.setDefaultEncoding(StandardCharsets.UTF_8.name());
        cfg.setOutputFormat(HTMLOutputFormat.INSTANCE);
        return new FreemarkerMailTemplateRenderer(cfg);
    }

    private MailTemplate buildGuardedTemplate(MailRateLimiter rateLimiter,
                                            MailDailyQuotaBreaker quotaBreaker,
                                            MailIdempotentGuard idempotentGuard,
                                            List<MailSendInterceptor> customInterceptors,
                                            MailSandboxProperties sandboxProps) {
        MailProperties effectiveProps = copyMailPropertiesWithSandbox(sandboxProps);
        List<MailSendInterceptor> interceptors = customInterceptors != null
                ? customInterceptors
                : mailTemplate.getInterceptors();
        return new MailTemplate(
                mailTemplate.getAccountRegistry(),
                effectiveProps,
                mailTemplate.getTemplateRenderer(),
                mailTemplate.getAccountResolver(),
                mailTemplate.getAccountProvider(),
                mailTemplate.getAccessTokenProvider(),
                mailTemplate.getExecutor(),
                mailTemplate.getEventPublisher(),
                mailTemplate.getFailureHandlers(),
                interceptors,
                rateLimiter,
                quotaBreaker,
                idempotentGuard,
                mailTemplate.getMetricsRecorder()
        );
    }

    private MailProperties copyMailPropertiesWithSandbox(MailSandboxProperties sandboxProps) {
        MailProperties copy = new MailProperties();
        copy.setDefaultAccount(mailProperties.getDefaultAccount());
        copy.setConnectionTimeout(Duration.ofMillis(200));
        copy.setReadTimeout(Duration.ofMillis(300));
        copy.setWriteTimeout(Duration.ofMillis(300));
        copy.setMaxAttachmentSize(mailProperties.getMaxAttachmentSize());
        copy.setMaxTotalSize(mailProperties.getMaxTotalSize());
        copy.setMaxDynamicAccounts(mailProperties.getMaxDynamicAccounts());
        copy.setArchiveBcc(mailProperties.getArchiveBcc());
        if (mailProperties.getAsync() != null) {
            copy.setAsync(mailProperties.getAsync());
        }
        if (mailProperties.getTemplate() != null) {
            copy.setTemplate(mailProperties.getTemplate());
        }
        copy.setBatch(mailProperties.getBatch());
        copy.setFailover(mailProperties.getFailover());
        copy.getRetry().setEnabled(false);
        if (sandboxProps != null) {
            copy.setSandbox(sandboxProps);
        }
        return copy;
    }

    private MailAccount buildUnreachablePrimaryAccount(List<String> backupAccounts, String defaultFrom) {
        int closedPort = allocateClosedPort();
        MailProperties fastFailProps = new MailProperties();
        fastFailProps.setConnectionTimeout(Duration.ofMillis(200));
        fastFailProps.setReadTimeout(Duration.ofMillis(200));
        fastFailProps.setWriteTimeout(Duration.ofMillis(200));

        MailAccount account = MailAccount.builder()
                .name("primary-unreachable-" + closedPort)
                .host("127.0.0.1")
                .port(closedPort)
                .authMode(AuthMode.NONE)
                .ssl(false)
                .starttls(false)
                .defaultFrom(defaultFrom != null ? defaultFrom : "primary@example.com")
                .backupAccounts(backupAccounts != null ? new ArrayList<>(backupAccounts) : new ArrayList<>())
                .build();
        account.setSender(MailAccountRegistry.createSender(account, fastFailProps));
        return account;
    }

    private static int allocateClosedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            return 59999;
        }
    }

    private static double readGauge(MeterRegistry registry, String name) {
        if (registry == null) {
            return 0.0;
        }
        Gauge gauge = registry.find(name).gauge();
        return gauge != null ? gauge.value() : 0.0;
    }

    private static double sumCounters(MeterRegistry registry, String name, String outcomeTag) {
        if (registry == null) {
            return 0.0;
        }
        Collection<Counter> counters = (outcomeTag != null)
                ? registry.find(name).tag(MailMetrics.TAG_OUTCOME, outcomeTag).counters()
                : registry.find(name).counters();
        double sum = 0.0;
        for (Counter counter : counters) {
            sum += counter.count();
        }
        return sum;
    }
}
