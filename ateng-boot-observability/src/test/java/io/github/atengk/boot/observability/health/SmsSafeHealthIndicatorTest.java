package io.github.atengk.boot.observability.health;

import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import io.github.atengk.boot.sms.guard.SmsChannelCircuitBreaker;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.properties.SmsProperties;
import io.github.atengk.boot.sms.provider.AliyunSmsProvider;
import io.github.atengk.boot.sms.provider.MockSmsProvider;
import io.github.atengk.boot.sms.provider.TencentSmsProvider;
import io.github.atengk.boot.sms.registry.SmsChannelProvider;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import io.github.atengk.boot.sms.template.SmsTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.support.StaticApplicationContext;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工单 #1149 SmsSafeHealthIndicator 单元测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("工单 #1149 SmsSafeHealthIndicator 短信安全健康探针测试")
class SmsSafeHealthIndicatorTest {

    @Test
    @DisplayName("未装配 SmsChannelRegistry 或静态渠道为空时返回 UNKNOWN，自动跳过动态缓存渠道且 0 真实短信发出")
    void shouldReturnUnknownWhenStaticChannelsEmptyAndIgnoreDynamicChannels() {
        StaticApplicationContext emptyCtx = new StaticApplicationContext();
        emptyCtx.refresh();

        SmsSafeHealthIndicator noRegistryIndicator = new SmsSafeHealthIndicator(
                emptyCtx.getBeanProvider(SmsChannelRegistry.class),
                emptyCtx.getBeanProvider(SmsChannelCircuitBreaker.class),
                emptyCtx.getBeanProvider(SmsTemplate.class)
        );
        assertThat(noRegistryIndicator.health().getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(noRegistryIndicator.getCacheTtl()).isEqualTo(Duration.ofSeconds(60));

        // 仅存在动态按需渠道缓存而无静态渠道 -> 依然返回 UNKNOWN
        SmsProperties smsProps = new SmsProperties();
        SmsChannelProvider dynProvider = name -> SmsChannel.builder()
                .name(name)
                .supplier(SmsSupplier.MOCK)
                .build();
        SmsChannelRegistry registry = new SmsChannelRegistry(smsProps, dynProvider);
        assertThat(registry.get("tenant-dynamic-1")).isNotNull();

        MockSmsProvider mockProvider = new MockSmsProvider(smsProps.getGuard());
        SmsTemplate template = new SmsTemplate(smsProps, List.of(mockProvider), registry,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        StaticApplicationContext ctx = new StaticApplicationContext();
        ctx.registerBean(SmsChannelRegistry.class, () -> registry);
        ctx.registerBean(SmsTemplate.class, () -> template);
        ctx.refresh();

        SmsSafeHealthIndicator indicator = new SmsSafeHealthIndicator(
                ctx.getBeanProvider(SmsChannelRegistry.class),
                ctx.getBeanProvider(SmsChannelCircuitBreaker.class),
                ctx.getBeanProvider(SmsTemplate.class)
        );
        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("staticChannelCount", 0);
        assertThat(health.getDetails()).containsEntry("dynamicChannelCount", 1);
        assertThat(mockProvider.getSentCount()).isZero();
    }

    @Test
    @DisplayName("静态渠道全部熔断返回 DOWN，存在健康静态渠道返回 UP 且全程 0 条计费短信")
    @SuppressWarnings("unchecked")
    void shouldReturnDownWhenAllStaticChannelsOpenAndUpWhenHealthyExists() {
        SmsProperties smsProps = new SmsProperties();
        smsProps.getGuard().setCircuitBreakerEnabled(true);
        smsProps.getGuard().setCircuitBreakerFailureThreshold(1);

        SmsChannelRegistry registry = new SmsChannelRegistry(smsProps);
        registry.register(SmsChannel.builder()
                .name("ali-primary")
                .supplier(SmsSupplier.ALIYUN)
                .accessKeyId("AK_STATIC_1")
                .accessKeySecret("SECRET_PLAIN_NEVER_LEAK")
                .signName("官方")
                .build());
        registry.register(SmsChannel.builder()
                .name("tx-backup")
                .supplier(SmsSupplier.TENCENT)
                .accessKeyId("AKID_STATIC_2")
                .accessKeySecret("SK_TX_NEVER_LEAK")
                .appId("1400001")
                .signName("官方")
                .build());

        SmsChannelCircuitBreaker cb = new SmsChannelCircuitBreaker(smsProps.getGuard());
        MockSmsProvider mockProvider = new MockSmsProvider(smsProps.getGuard());
        SmsTemplate template = new SmsTemplate(smsProps,
                List.of(mockProvider, new AliyunSmsProvider(), new TencentSmsProvider()),
                registry,
                null, null, null, null, cb, null, null, null, null, null, null, null, null, null, null);

        StaticApplicationContext ctx = new StaticApplicationContext();
        ctx.registerBean(SmsChannelRegistry.class, () -> registry);
        ctx.registerBean(SmsChannelCircuitBreaker.class, () -> cb);
        ctx.registerBean(SmsTemplate.class, () -> template);
        ctx.refresh();

        ObservabilityProperties obsProps = new ObservabilityProperties();
        obsProps.getHealth().getSms().setCacheTtl(Duration.ZERO);
        SmsSafeHealthIndicator indicator = new SmsSafeHealthIndicator(
                ctx.getBeanProvider(SmsChannelRegistry.class),
                ctx.getBeanProvider(SmsChannelCircuitBreaker.class),
                ctx.getBeanProvider(SmsTemplate.class),
                obsProps
        );

        // 1. 全健康 -> UP，明文密钥不可见
        Health upHealth = indicator.health();
        assertThat(upHealth.getStatus()).isEqualTo(Status.UP);
        assertThat(upHealth.getDetails()).containsEntry("healthyCount", 2).containsEntry("openCircuitCount", 0);
        assertThat(upHealth.getDetails().toString()).doesNotContain("SECRET_PLAIN_NEVER_LEAK");
        assertThat(upHealth.getDetails().toString()).doesNotContain("AK_STATIC_1");

        // 2. 仅主渠道熔断，备用健康 -> 仍 UP
        cb.recordFailure("ali-primary");
        Health partialHealth = indicator.health();
        assertThat(partialHealth.getStatus()).isEqualTo(Status.UP);
        assertThat(partialHealth.getDetails()).containsEntry("healthyCount", 1).containsEntry("openCircuitCount", 1);

        // 3. 所有静态渠道全熔断 -> DOWN
        cb.recordFailure("tx-backup");
        Health downHealth = indicator.health();
        assertThat(downHealth.getStatus()).isEqualTo(Status.DOWN);
        assertThat(downHealth.getDetails()).containsEntry("healthyCount", 0).containsEntry("openCircuitCount", 2);
        Map<String, Object> channelsMap = (Map<String, Object>) downHealth.getDetails().get("channels");
        assertThat(channelsMap).containsKeys("ali-primary", "tx-backup");
        assertThat(mockProvider.getSentCount()).isZero();
    }

    @Test
    @DisplayName("默认 60s 防刷缓存生效及 probeBalance 故障异常凭据严格脱敏")
    void shouldEnforce60sCacheAndMaskSecretsInBalanceProbeErrors() {
        SmsProperties smsProps = new SmsProperties();
        SmsChannelRegistry registry = new SmsChannelRegistry(smsProps);
        registry.register(SmsChannel.builder()
                .name("mock-bal")
                .supplier(SmsSupplier.MOCK)
                .accessKeyId("USER_KEY_SECRET_ABC")
                .accessKeySecret("RAW_PWD_SECRET_XYZ")
                .build());

        AtomicInteger balanceCalls = new AtomicInteger(0);
        MockSmsProvider countingMockProvider = new MockSmsProvider(smsProps.getGuard()) {
            @Override
            public io.github.atengk.boot.sms.model.SmsBalanceResult queryBalance(SmsChannel channel) {
                balanceCalls.incrementAndGet();
                return super.queryBalance(channel);
            }
        };
        countingMockProvider.setMockBalance(new BigDecimal("888.88"), 5000L);

        SmsTemplate template = new SmsTemplate(smsProps, List.of(countingMockProvider), registry,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        StaticApplicationContext ctx = new StaticApplicationContext();
        ctx.registerBean(SmsChannelRegistry.class, () -> registry);
        ctx.registerBean(SmsTemplate.class, () -> template);
        ctx.refresh();

        // 1. 默认 60s 缓存下连续两次 health() 仅触发 1 次余额探查
        ObservabilityProperties cachedProps = new ObservabilityProperties();
        cachedProps.getHealth().getSms().setProbeBalance(true);
        SmsSafeHealthIndicator cachedIndicator = new SmsSafeHealthIndicator(
                ctx.getBeanProvider(SmsChannelRegistry.class),
                ctx.getBeanProvider(SmsChannelCircuitBreaker.class),
                ctx.getBeanProvider(SmsTemplate.class),
                cachedProps
        );
        assertThat(cachedIndicator.getCacheTtl()).isEqualTo(Duration.ofSeconds(60));
        assertThat(cachedIndicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(cachedIndicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(balanceCalls.get()).isEqualTo(1);

        // 2. 探查异常包含敏感口令/Token 时，返回 DOWN 且脱敏详情零泄露
        countingMockProvider.setMockBalanceFailure(new IllegalStateException(
                "Auth rejected with accessKeySecret=RAW_PWD_SECRET_XYZ token=BEARER_999 key=USER_KEY_SECRET_ABC"
        ));
        ObservabilityProperties uncachedProps = new ObservabilityProperties();
        uncachedProps.getHealth().getSms().setCacheTtl(Duration.ZERO);
        uncachedProps.getHealth().getSms().setProbeBalance(true);
        SmsSafeHealthIndicator errIndicator = new SmsSafeHealthIndicator(
                ctx.getBeanProvider(SmsChannelRegistry.class),
                ctx.getBeanProvider(SmsChannelCircuitBreaker.class),
                ctx.getBeanProvider(SmsTemplate.class),
                uncachedProps
        );
        Health errHealth = errIndicator.health();
        assertThat(errHealth.getStatus()).isEqualTo(Status.DOWN);
        String text = errHealth.getDetails().toString();
        assertThat(text).doesNotContain("RAW_PWD_SECRET_XYZ");
        assertThat(text).doesNotContain("USER_KEY_SECRET_ABC");
        assertThat(text).doesNotContain("BEARER_999");
        assertThat(text).contains("******");
        assertThat(countingMockProvider.getSentCount()).isZero();
    }

    @Test
    @DisplayName("静态渠道配置了未注册的 providerCode 时探针应判为 configValid=false 并返回 DOWN")
    @SuppressWarnings("unchecked")
    void shouldReturnDownWhenStaticChannelProviderCodeIsUnregistered() {
        SmsProperties smsProps = new SmsProperties();
        SmsChannelRegistry registry = new SmsChannelRegistry(smsProps);
        registry.register(SmsChannel.builder()
                .name("custom-missing")
                .supplier(SmsSupplier.CUSTOM)
                .providerCode("unregistered-custom-provider")
                .build());

        MockSmsProvider mockProvider = new MockSmsProvider(smsProps.getGuard());
        SmsTemplate template = new SmsTemplate(smsProps, List.of(mockProvider), registry,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        StaticApplicationContext ctx = new StaticApplicationContext();
        ctx.registerBean(SmsChannelRegistry.class, () -> registry);
        ctx.registerBean(SmsTemplate.class, () -> template);
        ctx.refresh();

        ObservabilityProperties obsProps = new ObservabilityProperties();
        obsProps.getHealth().getSms().setCacheTtl(Duration.ZERO);
        SmsSafeHealthIndicator indicator = new SmsSafeHealthIndicator(
                ctx.getBeanProvider(SmsChannelRegistry.class),
                ctx.getBeanProvider(SmsChannelCircuitBreaker.class),
                ctx.getBeanProvider(SmsTemplate.class),
                obsProps
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("healthyCount", 0);
        Map<String, Object> channelsMap = (Map<String, Object>) health.getDetails().get("channels");
        Map<String, Object> chInfo = (Map<String, Object>) channelsMap.get("custom-missing");
        assertThat(chInfo).containsEntry("configValid", false).containsEntry("status", "DOWN");
    }
}
