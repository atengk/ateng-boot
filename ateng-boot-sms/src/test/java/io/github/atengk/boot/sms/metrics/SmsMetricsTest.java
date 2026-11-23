package io.github.atengk.boot.sms.metrics;

import io.github.atengk.boot.sms.config.AtengSmsAutoConfiguration;
import io.github.atengk.boot.sms.event.SmsSendAuditEvent;
import io.github.atengk.boot.sms.exception.SmsBudgetExceededException;
import io.github.atengk.boot.sms.guard.InMemoryDailyBudgetBreaker;
import io.github.atengk.boot.sms.guard.InMemorySmsBlacklistProvider;
import io.github.atengk.boot.sms.guard.InMemorySmsQuotaGuard;
import io.github.atengk.boot.sms.guard.SmsChannelCircuitBreaker;
import io.github.atengk.boot.sms.limiter.InMemorySmsRateLimiter;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.properties.SmsProperties;
import io.github.atengk.boot.sms.provider.AliyunSmsProvider;
import io.github.atengk.boot.sms.provider.MockSmsProvider;
import io.github.atengk.boot.sms.registry.SmsChannelProvider;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import io.github.atengk.boot.sms.template.SmsTemplate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工单 #1149 SmsMetrics 低基数度量指标与无高基数标签防爆炸单元测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("工单 #1149 SmsMetrics 低基数可观测度量测试")
class SmsMetricsTest {

    private MockWebServer mockWebServer;

    @AfterEach
    void tearDown() throws IOException {
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
    }

    @Test
    @DisplayName("精确记录成功、拦截、限流、预算熔断、Failover降级、HTTP重试与熔断器迁移指标")
    void shouldRecordLifecycleMetricsAccurately() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        // 阿里云连续 503 两次（第 1 次失败+重试1次=仍失败 -> 熔断器记失败并切换备通道）
        mockWebServer.enqueue(new MockResponse().setResponseCode(503).setBody("unavailable"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(503).setBody("unavailable"));

        AtomicReference<Instant> nowRef = new AtomicReference<>(Instant.parse("2026-11-21T12:00:00Z"));
        Clock mutableClock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return nowRef.get();
            }
        };

        SmsProperties props = new SmsProperties();
        props.getGuard().setCircuitBreakerEnabled(true);
        props.getGuard().setCircuitBreakerFailureThreshold(1);
        props.getGuard().setCircuitBreakerOpenDuration(Duration.ofSeconds(10));
        props.getGuard().setBlacklistEnabled(true);
        props.getGuard().setNotifyRateLimitEnabled(true);
        props.getRateLimit().setPerMinute(1);
        props.getRateLimit().setPerHour(10);
        props.getRateLimit().setPerDay(100);
        props.getGuard().setDailyMaxQuota(2L);

        SmsChannelRegistry registry = new SmsChannelRegistry(props);
        registry.register(SmsChannel.builder()
                .name("primary-ali")
                .supplier(SmsSupplier.ALIYUN)
                .accessKeyId("AK")
                .accessKeySecret("SK")
                .signName("官方")
                .endpoint(mockWebServer.url("/").toString())
                .maxRetries(1)
                .retryInterval(Duration.ofMillis(1))
                .failoverChannel("mock")
                .build());

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SmsMetrics smsMetrics = new SmsMetrics(meterRegistry, registry);
        SmsChannelCircuitBreaker cb = new SmsChannelCircuitBreaker(props.getGuard(), mutableClock);
        InMemorySmsBlacklistProvider blacklist = new InMemorySmsBlacklistProvider(props.getGuard().getBlacklist());
        blacklist.add("13800000002", "退订");
        InMemorySmsRateLimiter rateLimiter = new InMemorySmsRateLimiter(props.getRateLimit(), 1000, mutableClock);
        InMemoryDailyBudgetBreaker budgetBreaker = new InMemoryDailyBudgetBreaker(props.getGuard(), null, mutableClock);

        SmsTemplate template = new SmsTemplate(
                props,
                List.of(new MockSmsProvider(props.getGuard()), new AliyunSmsProvider()),
                registry,
                null, null,
                blacklist,
                budgetBreaker,
                cb,
                null, null, null, null,
                mutableClock,
                null,
                rateLimiter,
                null,
                null,
                smsMetrics
        );

        // 1. 主通道 503 重试 1 次后失败，触发熔断 CLOSED->OPEN 并 Failover 至 mock 成功
        template.send(SmsRequest.of("13800000001", "TPL_1").withChannel("primary-ali"));

        // 2. 命中黑名单 -> BLOCKED(blacklist)
        template.send(SmsRequest.of("13800000002", "TPL_1").withChannel("mock"));

        // 3. 第二次同手机号(13800000001)通知触发限流 -> RATE_LIMITED
        assertThatThrownBy(() -> template.send(SmsRequest.of("13800000001", "TPL_1").withChannel("mock")))
                .isInstanceOf(RuntimeException.class);

        // 4. 正常发送消耗第 2 笔每日预算，随后第 3 笔触发单日预算超限
        template.send(SmsRequest.of("13800000003", "TPL_1").withChannel("mock"));
        assertThatThrownBy(() -> template.send(SmsRequest.of("13800000004", "TPL_1").withChannel("mock")))
                .isInstanceOf(RuntimeException.class);

        // 验证计数与直方图
        Counter successAliCounter = meterRegistry.find(SmsMetrics.METRIC_SEND_COUNT)
                .tag("channel", "primary-ali").tag("outcome", "success").counter();
        assertThat(successAliCounter).isNotNull();
        assertThat(successAliCounter.count()).isEqualTo(1.0);

        Counter retryCounter = meterRegistry.find(SmsMetrics.METRIC_RETRY_COUNT)
                .tag("channel", "primary-ali").tag("supplier", "aliyun").counter();
        assertThat(retryCounter).isNotNull();
        assertThat(retryCounter.count()).isEqualTo(1.0);

        Counter failoverCounter = meterRegistry.find(SmsMetrics.METRIC_FAILOVER_COUNT)
                .tag("channel", "primary-ali").tag("outcome", "success").counter();
        assertThat(failoverCounter).isNotNull();
        assertThat(failoverCounter.count()).isEqualTo(1.0);

        Counter cbOpenCounter = meterRegistry.find(SmsMetrics.METRIC_CB_TRANSITION_COUNT)
                .tag("channel", "primary-ali").tag("from", "CLOSED").tag("to", "OPEN").counter();
        assertThat(cbOpenCounter).isNotNull();
        assertThat(cbOpenCounter.count()).isEqualTo(1.0);

        Counter blockedCounter = meterRegistry.find(SmsMetrics.METRIC_INTERCEPTED_COUNT)
                .tag("channel", "mock").tag("reason", "blacklist").counter();
        assertThat(blockedCounter).isNotNull();
        assertThat(blockedCounter.count()).isEqualTo(1.0);

        Counter rateLimitCounter = meterRegistry.find(SmsMetrics.METRIC_RATE_LIMITED_COUNT)
                .tag("channel", "mock").tag("namespace", "notify").counter();
        assertThat(rateLimitCounter).isNotNull();
        assertThat(rateLimitCounter.count()).isEqualTo(1.0);

        Counter budgetCounter = meterRegistry.find(SmsMetrics.METRIC_SEND_COUNT)
                .tag("channel", "mock").tag("outcome", "budget_exceeded").counter();
        assertThat(budgetCounter).isNotNull();
        assertThat(budgetCounter.count()).isEqualTo(1.0);

        Timer sendTimer = meterRegistry.find(SmsMetrics.METRIC_SEND_DURATION)
                .tag("channel", "primary-ali").tag("outcome", "success").timer();
        assertThat(sendTimer).isNotNull();
        assertThat(sendTimer.count()).isEqualTo(1L);

        // 推进时钟触发半开探测恢复 OPEN -> HALF_OPEN -> CLOSED
        nowRef.set(Instant.parse("2026-11-21T12:00:15Z"));
        assertThat(cb.allowRequest("primary-ali")).isTrue();
        cb.recordSuccess("primary-ali");
        assertThat(meterRegistry.find(SmsMetrics.METRIC_CB_TRANSITION_COUNT)
                .tag("from", "OPEN").tag("to", "HALF_OPEN").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.find(SmsMetrics.METRIC_CB_TRANSITION_COUNT)
                .tag("from", "HALF_OPEN").tag("to", "CLOSED").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("严格禁止高基数维度爆炸与手机号泄漏：动态租户统一收敛为 dynamic，独立模式上限收敛为 other")
    void shouldPreventHighCardinalityAndPhoneTagLeakage() {
        SmsProperties props = new SmsProperties();
        SmsChannelProvider dynamicProvider = name -> SmsChannel.builder()
                .name(name)
                .supplier(SmsSupplier.MOCK)
                .defaultTemplateId("TPL_1")
                .build();
        SmsChannelRegistry registry = new SmsChannelRegistry(props, dynamicProvider);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SmsMetrics smsMetrics = new SmsMetrics(meterRegistry, registry);

        SmsTemplate template = new SmsTemplate(
                props,
                List.of(new MockSmsProvider(props.getGuard())),
                registry,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                smsMetrics
        );

        // 模拟 80 个不同动态租户与 80 个不同手机号
        for (int i = 0; i < 80; i++) {
            String dynamicTenantCh = "tenant-dyn-" + i;
            String phone = "1391234" + String.format("%04d", i);
            template.send(SmsRequest.of(phone, "TPL_1").withChannel(dynamicTenantCh));
        }

        // 1. 绑定 registry 时所有动态渠道统一收敛为 channel="dynamic"，绝不产生 80 条时间序列
        Counter dynCounter = meterRegistry.find(SmsMetrics.METRIC_SEND_COUNT)
                .tag("channel", "dynamic")
                .tag("supplier", "mock")
                .tag("outcome", "success")
                .counter();
        assertThat(dynCounter).isNotNull();
        assertThat(dynCounter.count()).isEqualTo(80.0);

        // 2. 全面扫描所有 Meter Tag：绝不允许任何 tagKey=phone 或任何手机号/动态租户名进入 tagValue
        for (Meter meter : meterRegistry.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                assertThat(tag.getKey()).doesNotContainIgnoringCase("phone");
                assertThat(tag.getValue()).doesNotStartWith("139");
                assertThat(tag.getValue()).doesNotStartWith("tenant-dyn-");
            }
        }

        // 3. 独立使用未绑定 Registry 时最多追踪 64 个静态基数，第 65 起全部收敛为 "other"
        SimpleMeterRegistry standaloneRegistry = new SimpleMeterRegistry();
        SmsMetrics standaloneMetrics = new SmsMetrics(standaloneRegistry);
        for (int i = 0; i < 100; i++) {
            standaloneMetrics.recordSend("standalone-" + i, "mock", SmsSendOutcome.SUCCESS, Duration.ofMillis(5));
        }
        Counter otherCounter = standaloneRegistry.find(SmsMetrics.METRIC_SEND_COUNT)
                .tag("channel", "other").counter();
        assertThat(otherCounter).isNotNull();
        assertThat(otherCounter.count()).isEqualTo(36.0);
    }

    @Test
    @DisplayName("自动装配条件验证：存在 MeterRegistry 自动注册 SmsMetrics，缺失时平滑降级零异常")
    void shouldAutoConfigureSmsMetricsConditionally() {
        ApplicationContextRunner baseRunner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AtengSmsAutoConfiguration.class));

        baseRunner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(SmsMetrics.class);
            assertThat(ctx).hasSingleBean(SmsTemplate.class);
            assertThat(ctx.getBean(SmsTemplate.class).getMetricsRecorder()).isSameAs(SmsMetricsRecorder.noop());
        });

        baseRunner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(SmsMetrics.class);
                    SmsTemplate tpl = ctx.getBean(SmsTemplate.class);
                    assertThat(tpl.getMetricsRecorder()).isInstanceOf(SmsMetrics.class);
                });
    }

    @Test
    @DisplayName("负载均衡全渠道配额耗尽时精准记录 quota_exceeded 度量并触发发送审计事件")
    void shouldRecordQuotaExceededAndAuditForLoadBalanceExhaustion() {
        SmsProperties props = new SmsProperties();
        SmsChannelRegistry registry = new SmsChannelRegistry(props);
        registry.register(SmsChannel.builder()
                .name("lb-q-1")
                .supplier(SmsSupplier.MOCK)
                .defaultTemplateId("TPL_Q")
                .maxQuota(1)
                .weight(1)
                .build());

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SmsMetrics smsMetrics = new SmsMetrics(meterRegistry, registry);
        InMemorySmsQuotaGuard quotaGuard = new InMemorySmsQuotaGuard(Clock.systemDefaultZone());
        List<SmsSendAuditEvent> audits = new java.util.ArrayList<>();

        SmsTemplate template = new SmsTemplate(
                props,
                List.of(new MockSmsProvider(props.getGuard())),
                registry,
                null, null, null, null, null, null, null,
                event -> {
                    if (event instanceof SmsSendAuditEvent audit) {
                        audits.add(audit);
                    }
                },
                null,
                Clock.systemDefaultZone(),
                null, null,
                quotaGuard,
                null,
                smsMetrics
        );

        // 第 1 次成功消耗唯一配额
        assertThat(template.sendByLoadBalance(SmsRequest.of("13800001001", "TPL_Q")).isSuccess()).isTrue();

        // 第 2 次通过 sendByLoadBalance 触发全渠道配额耗尽：抛出异常且记录 quota_exceeded 与失败审计
        assertThatThrownBy(() -> template.sendByLoadBalance(SmsRequest.of("13800001002", "TPL_Q")))
                .isInstanceOf(SmsBudgetExceededException.class)
                .satisfies(ex -> assertThat(((SmsBudgetExceededException) ex).isChannelQuota()).isTrue());

        // 第 3 次直接传 channel="load-balance" 同样准确记录为 quota_exceeded（非 budget_exceeded）
        assertThatThrownBy(() -> template.send(SmsRequest.of("13800001003", "TPL_Q").withChannel(SmsTemplate.LOAD_BALANCE_CHANNEL)))
                .isInstanceOf(SmsBudgetExceededException.class)
                .satisfies(ex -> assertThat(((SmsBudgetExceededException) ex).isChannelQuota()).isTrue());

        Counter quotaCounter = meterRegistry.find(SmsMetrics.METRIC_SEND_COUNT)
                .tag("outcome", "quota_exceeded")
                .counter();
        assertThat(quotaCounter).isNotNull();
        assertThat(quotaCounter.count()).isEqualTo(2.0);
        assertThat(meterRegistry.find(SmsMetrics.METRIC_SEND_COUNT)
                .tag("outcome", "budget_exceeded")
                .counter()).isNull();
        assertThat(audits).hasSize(3);
        assertThat(audits.get(1).isSuccess()).isFalse();
        assertThat(audits.get(2).isSuccess()).isFalse();
    }
}
