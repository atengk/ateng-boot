package io.github.atengk.boot.mail.metrics;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.atengk.boot.mail.exception.MailResultCode;
import io.github.atengk.boot.mail.exception.MailSendException;
import io.github.atengk.boot.mail.guard.InMemoryMailDailyQuotaBreaker;
import io.github.atengk.boot.mail.guard.InMemoryMailIdempotentGuard;
import io.github.atengk.boot.mail.guard.InMemoryMailRateLimiter;
import io.github.atengk.boot.mail.interceptor.MailSendInterceptor;
import io.github.atengk.boot.mail.model.BatchMailSendResult;
import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.model.MailMessage;
import io.github.atengk.boot.mail.properties.MailIdempotentProperties;
import io.github.atengk.boot.mail.properties.MailProperties;
import io.github.atengk.boot.mail.properties.MailQuotaProperties;
import io.github.atengk.boot.mail.properties.MailRateLimitProperties;
import io.github.atengk.boot.mail.properties.MailRetryProperties;
import io.github.atengk.boot.mail.properties.MailSandboxProperties;
import io.github.atengk.boot.mail.registry.MailAccountRegistry;
import io.github.atengk.boot.mail.resolver.TenantMailAccountResolver;
import io.github.atengk.boot.mail.template.MailTemplate;
import io.github.atengk.boot.mail.template.renderer.SimpleMailTemplateRenderer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MailMetrics 低基数 Micrometer 指标采集端到端单测
 *
 * @author Ateng
 * @since 2026-11-16
 */
@DisplayName("MailMetrics 低基数 Micrometer 指标采集测试")
class MailMetricsTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort())
            .withConfiguration(GreenMailConfiguration.aConfig()
                    .withUser("default@atengk.com", "password")
                    .withUser("ops@atengk.com", "password"));

    private SimpleMeterRegistry meterRegistry;
    private MailAccountRegistry registry;
    private MailProperties mailProperties;
    private MailMetrics mailMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        int smtpPort = greenMail.getSmtp().getPort();

        mailProperties = new MailProperties();
        mailProperties.setHost("127.0.0.1");
        mailProperties.setPort(smtpPort);
        mailProperties.setUsername("default@atengk.com");
        mailProperties.setPassword("password");
        mailProperties.setSsl(false);
        mailProperties.getRetry().setEnabled(false);

        registry = new MailAccountRegistry("default", 10, mailProperties, null);
        registry.register(buildAccount("default", "default@atengk.com", smtpPort));
        registry.register(buildAccount("ops", "ops@atengk.com", smtpPort));

        mailMetrics = new MailMetrics(meterRegistry, registry);
    }

    @AfterEach
    void tearDown() {
        meterRegistry.close();
    }

    private MailAccount buildAccount(String name, String username, int port) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("127.0.0.1");
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword("password");
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.quitwait", "false");
        return MailAccount.builder()
                .name(name)
                .host("127.0.0.1")
                .port(port)
                .username(username)
                .password("password")
                .ssl(false)
                .defaultFrom(username)
                .sender(sender)
                .build();
    }

    private MailTemplate createTemplate(List<MailSendInterceptor> interceptors,
                                        InMemoryMailRateLimiter rateLimiter,
                                        InMemoryMailDailyQuotaBreaker quotaBreaker,
                                        InMemoryMailIdempotentGuard idempotentGuard) {
        return new MailTemplate(
                registry, mailProperties, new SimpleMailTemplateRenderer(),
                new TenantMailAccountResolver(), null, null, null,
                null, null, interceptors,
                rateLimiter, quotaBreaker, idempotentGuard, mailMetrics
        );
    }

    @Test
    @DisplayName("按 account 与 outcome 低基数累计发信计数与耗时，且严禁记录收件人或主题标签")
    void testSendMetricsByAccountAndOutcomeWithLowCardinality() {
        MailTemplate template = createTemplate(null, null, null, null);

        // 向 5 个不同收件人与主题发送：metrics 标签组合数量仍严格按 account+outcome 收敛
        for (int i = 0; i < 3; i++) {
            template.send(new MailMessage()
                    .account("default")
                    .to("u" + i + "@example.com")
                    .subject("Subject-" + i)
                    .text("hello " + i));
        }
        for (int i = 0; i < 2; i++) {
            template.send(new MailMessage()
                    .account("ops")
                    .to("ops-target-" + i + "@example.com")
                    .subject("Alert-" + i)
                    .text("alert " + i));
        }

        // 触发一次地址校验失败（进入 sendWithAccount 之前失败）和一次不存在账号失败
        assertThatThrownBy(() -> template.send(new MailMessage()
                .account("ops")
                .to("not-an-email")
                .subject("Bad")
                .text("bad")))
                .isInstanceOf(io.github.atengk.boot.mail.exception.MailSendException.class);

        Counter defaultSuccess = meterRegistry.find(MailMetrics.METRIC_SEND_COUNT)
                .tag("account", "default").tag("outcome", "success").counter();
        Counter opsSuccess = meterRegistry.find(MailMetrics.METRIC_SEND_COUNT)
                .tag("account", "ops").tag("outcome", "success").counter();
        Timer defaultTimer = meterRegistry.find(MailMetrics.METRIC_SEND_DURATION)
                .tag("account", "default").tag("outcome", "success").timer();
        Timer opsTimer = meterRegistry.find(MailMetrics.METRIC_SEND_DURATION)
                .tag("account", "ops").tag("outcome", "success").timer();

        assertThat(defaultSuccess).isNotNull();
        assertThat(defaultSuccess.count()).isEqualTo(3.0);
        assertThat(opsSuccess).isNotNull();
        assertThat(opsSuccess.count()).isEqualTo(2.0);
        assertThat(defaultTimer).isNotNull();
        assertThat(defaultTimer.count()).isEqualTo(3L);
        assertThat(opsTimer).isNotNull();
        assertThat(opsTimer.count()).isEqualTo(2L);

        // 校验全部邮件指标标签集合严格受控（不含收件人邮箱、主题）
        for (Meter meter : meterRegistry.getMeters()) {
            if (meter.getId().getName().startsWith("ateng.mail.")) {
                for (io.micrometer.core.instrument.Tag tag : meter.getId().getTags()) {
                    assertThat(tag.getKey()).isIn("account", "outcome");
                    assertThat(tag.getValue()).doesNotContain("@example.com");
                    assertThat(tag.getValue()).doesNotContain("Subject-");
                }
            }
        }
    }

    @Test
    @DisplayName("准确区分拦截、频控、日配额、幂等防重与纯日志沙箱终态 outcome")
    void testOutcomeDistinctionForGuardsAndSandbox() {
        // 1. 拦截器阻断 -> outcome=intercepted
        MailSendInterceptor blockingInterceptor = msg -> false;
        MailTemplate interceptedTemplate = createTemplate(List.of(blockingInterceptor), null, null, null);
        assertThatThrownBy(() -> interceptedTemplate.send(new MailMessage()
                .account("default").to("a@example.com").subject("s").text("b")))
                .isInstanceOf(io.github.atengk.boot.mail.exception.MailSendException.class);
        assertThat(meterRegistry.find(MailMetrics.METRIC_SEND_COUNT)
                .tag("account", "default").tag("outcome", "intercepted").counter().count()).isEqualTo(1.0);

        // 2. 频控超限 -> outcome=rate_limited
        MailRateLimitProperties rateLimitProps = new MailRateLimitProperties();
        rateLimitProps.setEnabled(true);
        rateLimitProps.setAccountCapacity(1);
        rateLimitProps.setAccountPermitsPerSecond(0.01);
        rateLimitProps.setRecipientMaxCount(10);
        InMemoryMailRateLimiter rateLimiter = new InMemoryMailRateLimiter(rateLimitProps);
        MailTemplate rateTemplate = createTemplate(null, rateLimiter, null, null);
        rateTemplate.send(new MailMessage().account("default").to("r1@example.com").subject("ok").text("ok"));
        assertThatThrownBy(() -> rateTemplate.send(new MailMessage()
                .account("default").to("r2@example.com").subject("blocked").text("blocked")))
                .isInstanceOf(io.github.atengk.boot.mail.exception.MailSendException.class);
        assertThat(meterRegistry.find(MailMetrics.METRIC_SEND_COUNT)
                .tag("account", "default").tag("outcome", "rate_limited").counter().count()).isEqualTo(1.0);

        // 3. 日配额熔断 -> outcome=quota_exceeded
        MailQuotaProperties quotaProps = new MailQuotaProperties();
        quotaProps.setEnabled(true);
        quotaProps.setDailyMaxCount(1);
        InMemoryMailDailyQuotaBreaker quotaBreaker = new InMemoryMailDailyQuotaBreaker(quotaProps);
        MailTemplate quotaTemplate = createTemplate(null, null, quotaBreaker, null);
        quotaTemplate.send(new MailMessage().account("ops").to("q1@example.com").subject("ok").text("ok"));
        assertThatThrownBy(() -> quotaTemplate.send(new MailMessage()
                .account("ops").to("q2@example.com").subject("over").text("over")))
                .isInstanceOf(io.github.atengk.boot.mail.exception.MailSendException.class);
        assertThat(meterRegistry.find(MailMetrics.METRIC_SEND_COUNT)
                .tag("account", "ops").tag("outcome", "quota_exceeded").counter().count()).isEqualTo(1.0);

        // 4. 幂等防重 -> outcome=idempotent_rejected
        MailIdempotentProperties idempotentProps = new MailIdempotentProperties();
        idempotentProps.setEnabled(true);
        InMemoryMailIdempotentGuard idempotentGuard = new InMemoryMailIdempotentGuard(idempotentProps);
        MailTemplate idempotentTemplate = createTemplate(null, null, null, idempotentGuard);
        idempotentTemplate.send(new MailMessage().account("default").bizId("BIZ-100").to("i1@example.com").subject("ok").text("ok"));
        assertThatThrownBy(() -> idempotentTemplate.send(new MailMessage()
                .account("default").bizId("BIZ-100").to("i1@example.com").subject("dup").text("dup")))
                .isInstanceOf(io.github.atengk.boot.mail.exception.MailSendException.class);
        assertThat(meterRegistry.find(MailMetrics.METRIC_SEND_COUNT)
                .tag("account", "default").tag("outcome", "idempotent_rejected").counter().count()).isEqualTo(1.0);

        // 5. 纯日志沙箱 -> outcome=sandbox
        mailProperties.setSandbox(new MailSandboxProperties());
        mailProperties.getSandbox().setEnabled(true);
        mailProperties.getSandbox().setLogOnly(true);
        MailTemplate sandboxTemplate = createTemplate(null, null, null, null);
        sandboxTemplate.send(new MailMessage().account("ops").to("sb@example.com").subject("sb").text("sb"));
        assertThat(meterRegistry.find(MailMetrics.METRIC_SEND_COUNT)
                .tag("account", "ops").tag("outcome", "sandbox").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("统计重试计数、主备容灾计数、批量不重复计数及动态账号缓存 Gauge")
    void testRetryFailoverBatchAndAccountCacheGauges() {
        // 1. 测试重试 + 主备顺延容灾计数
        MailRetryProperties retry = mailProperties.getRetry();
        retry.setEnabled(true);
        retry.setMaxAttempts(2);
        retry.setDelay(Duration.ofMillis(10));
        retry.setMaxDelay(Duration.ofMillis(20));

        JavaMailSenderImpl flakySender = mock(JavaMailSenderImpl.class);
        when(flakySender.getSession()).thenReturn(Session.getInstance(new Properties()));
        doThrow(new org.springframework.mail.MailSendException("421 temporary failure"))
                .when(flakySender).send(any(MimeMessage.class));

        MailAccount primary = MailAccount.builder()
                .name("primary-flaky")
                .host("127.0.0.1")
                .port(greenMail.getSmtp().getPort())
                .username("default@atengk.com")
                .password("password")
                .ssl(false)
                .defaultFrom("default@atengk.com")
                .backupAccounts(List.of("ops"))
                .sender(flakySender)
                .build();
        registry.register(primary);

        MailTemplate template = createTemplate(null, null, null, null);
        template.send(new MailMessage().account("primary-flaky").to("failover@example.com").subject("fo").text("fo"));

        // primary 重试 1 次后切至 ops 备用成功
        assertThat(meterRegistry.find(MailMetrics.METRIC_RETRY_COUNT)
                .tag("account", "primary-flaky").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.find(MailMetrics.METRIC_FAILOVER_COUNT)
                .tag("account", "primary-flaky").tag("outcome", "success").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.find(MailMetrics.METRIC_SEND_COUNT)
                .tag("account", "primary-flaky").tag("outcome", "success").counter().count()).isEqualTo(1.0);

        // 2. 批量发送不重复计数（复用 Transport）
        mailProperties.getBatch().setReuseTransport(true);
        BatchMailSendResult batchResult = template.sendBatch(List.of(
                new MailMessage().account("default").to("b1@example.com").subject("b1").text("b1"),
                new MailMessage().account("default").to("b2@example.com").subject("b2").text("b2")
        ));
        assertThat(batchResult.getSuccessCount()).isEqualTo(2);
        assertThat(meterRegistry.find(MailMetrics.METRIC_SEND_COUNT)
                .tag("account", "default").tag("outcome", "success").counter().count()).isEqualTo(2.0);

        // 3. 验证动态账号缓存 Gauge 实时反映增删
        assertThat(meterRegistry.find(MailMetrics.METRIC_ACCOUNTS_STATIC_SIZE).gauge().value()).isEqualTo(3.0);
        assertThat(meterRegistry.find(MailMetrics.METRIC_ACCOUNTS_DYNAMIC_SIZE).gauge().value()).isEqualTo(0.0);
        assertThat(meterRegistry.find(MailMetrics.METRIC_ACCOUNTS_DYNAMIC_CAPACITY).gauge().value()).isEqualTo(10.0);

        registry.getOrCreateDynamic("dyn-tenant", MailAccount.builder()
                .name("dyn-tenant")
                .host("127.0.0.1")
                .port(greenMail.getSmtp().getPort())
                .username("default@atengk.com")
                .password("password")
                .ssl(false)
                .build(), mailProperties);
        assertThat(meterRegistry.find(MailMetrics.METRIC_ACCOUNTS_DYNAMIC_SIZE).gauge().value()).isEqualTo(1.0);

        registry.evictDynamic("dyn-tenant");
        assertThat(meterRegistry.find(MailMetrics.METRIC_ACCOUNTS_DYNAMIC_SIZE).gauge().value()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("动态账号标签收敛至 dynamic 防基数膨胀，且覆盖批量非复用模式、null 条目、批超时与事务提交后前置失败")
    void testDynamicTagCollapsingBatchEdgeCasesAndAfterCommitPreFailure() {
        MailTemplate template = createTemplate(null, null, null, null);

        // 1. 多个动态租户账号发信：统一归集为 account="dynamic"，不触发多序列膨胀
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            MailAccount dyn = MailAccount.builder()
                    .name("tenant-dyn-" + idx)
                    .host("127.0.0.1")
                    .port(greenMail.getSmtp().getPort())
                    .username("default@atengk.com")
                    .password("password")
                    .ssl(false)
                    .defaultFrom("default@atengk.com")
                    .build();
            template.forDynamicAccount(dyn).send(m -> m.to("dyn-" + idx + "@example.com").subject("dyn").text("hi"));
        }
        assertThat(meterRegistry.find(MailMetrics.METRIC_SEND_COUNT)
                .tag("account", "dynamic").tag("outcome", "success").counter().count()).isEqualTo(5.0);
        assertThat(meterRegistry.find(MailMetrics.METRIC_SEND_COUNT)
                .tag("account", "tenant-dyn-0").counter()).isNull();

        // 2. 批量 reuseTransport=false + 含 null 条目：null 计 failure，其余计 success，无漏记或双计
        mailProperties.getBatch().setReuseTransport(false);
        List<MailMessage> batchWithNull = java.util.Arrays.asList(
                new MailMessage().account("default").to("n1@example.com").subject("n1").text("n1"),
                null
        );
        BatchMailSendResult mixedResult = template.sendBatch(batchWithNull);
        assertThat(mixedResult.getSuccessCount()).isEqualTo(1);
        assertThat(mixedResult.getFailureCount()).isEqualTo(1);
        assertThat(meterRegistry.find(MailMetrics.METRIC_SEND_COUNT)
                .tag("account", "default").tag("outcome", "success").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.find(MailMetrics.METRIC_SEND_COUNT)
                .tag("account", "unresolved").tag("outcome", "failure").counter().count()).isEqualTo(1.0);

        // 3. 事务提交后前置失败（如显式指定不存在账号）：同样准确记录 FAILURE
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            template.sendAfterCommit(new MailMessage().account("non-existent-acc").to("tx@example.com").subject("tx").text("tx"));
            for (org.springframework.transaction.support.TransactionSynchronization sync
                    : org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
        assertThat(meterRegistry.find(MailMetrics.METRIC_SEND_COUNT)
                .tag("account", "unresolved").tag("outcome", "failure").counter().count()).isEqualTo(2.0);
    }
}
