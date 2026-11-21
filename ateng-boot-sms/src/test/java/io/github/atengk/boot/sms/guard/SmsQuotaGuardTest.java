package io.github.atengk.boot.sms.guard;

import io.github.atengk.boot.sms.exception.SmsBudgetExceededException;
import io.github.atengk.boot.sms.loadbalancer.SmsChannelLoadBalancer;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsQuotaResetCycle;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.model.SmsResult;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.properties.SmsProperties;
import io.github.atengk.boot.sms.provider.MockSmsProvider;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import io.github.atengk.boot.sms.template.SmsTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工单 #1148 单渠道配额上限、跨日时钟复位、负载均衡跳过与容灾联动测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("工单 #1148 单渠道配额守卫与容灾联动测试")
class SmsQuotaGuardTest {

    @Test
    @DisplayName("DAILY 触限自动顺延 Failover 备用渠道，跨日时钟推进后自动复位，全触限抛 SmsBudgetExceededException")
    void shouldFailoverOnDailyQuotaAndResetAfterMidnightClockAdvance() {
        AtomicReference<Instant> nowRef = new AtomicReference<>(Instant.parse("2026-11-21T10:00:00Z"));
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
        SmsChannelRegistry registry = new SmsChannelRegistry(props);
        registry.register(SmsChannel.builder()
                .name("ch-primary")
                .supplier(SmsSupplier.MOCK)
                .defaultTemplateId("TPL_1")
                .maxQuota(2)
                .quotaResetCycle(SmsQuotaResetCycle.DAILY)
                .failoverChannel("ch-backup")
                .build());
        registry.register(SmsChannel.builder()
                .name("ch-backup")
                .supplier(SmsSupplier.MOCK)
                .defaultTemplateId("TPL_1")
                .maxQuota(1)
                .quotaResetCycle(SmsQuotaResetCycle.DAILY)
                .build());

        InMemorySmsQuotaGuard quotaGuard = new InMemorySmsQuotaGuard(mutableClock);
        SmsChannelCircuitBreaker circuitBreaker = new SmsChannelCircuitBreaker(props.getGuard(), mutableClock);
        SmsChannelLoadBalancer loadBalancer = new SmsChannelLoadBalancer(registry, circuitBreaker, quotaGuard);
        MockSmsProvider mockProvider = new MockSmsProvider(props.getGuard());

        SmsTemplate template = new SmsTemplate(
                props,
                List.of(mockProvider),
                registry,
                null, null, null, null,
                circuitBreaker,
                loadBalancer,
                null, null, null, mutableClock, null, null, quotaGuard
        );

        // 第 1、2 次命中 ch-primary
        assertThat(template.send(SmsRequest.of("13800000001", "TPL_1").withChannel("ch-primary")).getChannel())
                .isEqualTo("ch-primary");
        assertThat(template.send(SmsRequest.of("13800000002", "TPL_1").withChannel("ch-primary")).getChannel())
                .isEqualTo("ch-primary");
        assertThat(template.getChannelQuotaUsed("ch-primary")).isEqualTo(2L);

        // 第 3 次 ch-primary 触限，自动短路跳过并容灾切换至 ch-backup（且不污染 ch-primary 熔断器）
        SmsResult failoverRes = template.send(SmsRequest.of("13800000003", "TPL_1").withChannel("ch-primary"));
        assertThat(failoverRes.getChannel()).isEqualTo("ch-backup");
        assertThat(template.isChannelOpen("ch-primary")).isFalse();
        assertThat(template.getChannelQuotaUsed("ch-backup")).isEqualTo(1L);

        // 第 4 次主备全部触限，抛出 SmsBudgetExceededException
        assertThatThrownBy(() -> template.send(SmsRequest.of("13800000004", "TPL_1").withChannel("ch-primary")))
                .isInstanceOf(SmsBudgetExceededException.class);

        // 跨日推进时钟到 2026-11-22 -> DAILY 自动清零恢复
        nowRef.set(Instant.parse("2026-11-22T00:05:00Z"));
        assertThat(template.getChannelQuotaUsed("ch-primary")).isZero();
        assertThat(template.getChannelQuotaUsed("ch-backup")).isZero();

        SmsResult nextDayRes = template.send(SmsRequest.of("13800000005", "TPL_1").withChannel("ch-primary"));
        assertThat(nextDayRes.getChannel()).isEqualTo("ch-primary");
    }

    @Test
    @DisplayName("TOTAL 包干配额跨日不自动复位直至手动重置，LoadBalancer 自动跳过触限渠道")
    void shouldPersistTotalQuotaAcrossMidnightAndSkipExhaustedChannelInLoadBalancer() {
        AtomicReference<Instant> nowRef = new AtomicReference<>(Instant.parse("2026-11-21T23:55:00Z"));
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
        SmsChannelRegistry registry = new SmsChannelRegistry(props);
        registry.register(SmsChannel.builder()
                .name("lb-total-a")
                .supplier(SmsSupplier.MOCK)
                .defaultTemplateId("TPL_1")
                .weight(10)
                .maxQuota(1)
                .quotaResetCycle(SmsQuotaResetCycle.TOTAL)
                .build());
        registry.register(SmsChannel.builder()
                .name("lb-total-b")
                .supplier(SmsSupplier.MOCK)
                .defaultTemplateId("TPL_1")
                .weight(1)
                .maxQuota(2)
                .quotaResetCycle(SmsQuotaResetCycle.TOTAL)
                .build());

        InMemorySmsQuotaGuard quotaGuard = new InMemorySmsQuotaGuard(mutableClock);
        SmsChannelLoadBalancer loadBalancer = new SmsChannelLoadBalancer(registry, null, quotaGuard);
        SmsTemplate template = new SmsTemplate(
                props,
                List.of(new MockSmsProvider(props.getGuard())),
                registry,
                null, null, null, null, null,
                loadBalancer,
                null, null, null, mutableClock, null, null, quotaGuard
        );

        // 第 1 次选高权重 lb-total-a（耗尽其 TOTAL=1）
        assertThat(template.sendByLoadBalance(SmsRequest.of("13800001111", "TPL_1")).getChannel())
                .isEqualTo("lb-total-a");

        // 推进时钟跨天：TOTAL 维持累计，LoadBalancer 自动跳过 lb-total-a 路由至 lb-total-b
        nowRef.set(Instant.parse("2026-11-22T00:10:00Z"));
        assertThat(template.getChannelQuotaUsed("lb-total-a")).isEqualTo(1L);
        assertThat(template.sendByLoadBalance(SmsRequest.of("13800001112", "TPL_1")).getChannel())
                .isEqualTo("lb-total-b");
        assertThat(template.sendByLoadBalance(SmsRequest.of("13800001113", "TPL_1")).getChannel())
                .isEqualTo("lb-total-b");

        // 全部 TOTAL 耗尽 -> 抛 SmsBudgetExceededException
        assertThatThrownBy(() -> template.sendByLoadBalance(SmsRequest.of("13800001114", "TPL_1")))
                .isInstanceOf(SmsBudgetExceededException.class);

        // 手动重置 lb-total-a 后恢复可用
        template.resetChannelQuota("lb-total-a");
        assertThat(template.getChannelQuotaUsed("lb-total-a")).isZero();
        assertThat(template.sendByLoadBalance(SmsRequest.of("13800001115", "TPL_1")).getChannel())
                .isEqualTo("lb-total-a");
    }

    @Test
    @DisplayName("RedisSmsQuotaGuard: Lua 原子拦截与 Redis 异常下安全降级放行")
    void shouldEnforceRedisLuaQuotaAndFailOpenOnRedisException() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-21T08:00:00Z"), ZoneOffset.UTC);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisSmsQuotaGuard redisGuard = new RedisSmsQuotaGuard(redisTemplate, clock);

        SmsChannel channel = SmsChannel.builder()
                .name("redis-ch")
                .supplier(SmsSupplier.MOCK)
                .maxQuota(5)
                .quotaResetCycle(SmsQuotaResetCycle.DAILY)
                .build();

        when(redisTemplate.execute(any(), anyList(), any(), any(), any())).thenReturn(1L, -1L);
        assertThat(redisGuard.tryAcquire(channel, 1)).isTrue();
        assertThat(redisGuard.tryAcquire(channel, 1)).isFalse();

        // Redis 通信异常时不抛未捕获异常，安全降级放行
        when(redisTemplate.execute(any(), anyList(), any(), any(), any()))
                .thenThrow(new RuntimeException("Redis connection refused"));
        assertThat(redisGuard.tryAcquire(channel, 1)).isTrue();
        assertThat(redisGuard.isQuotaAvailable(channel)).isTrue();
        assertThat(redisGuard.getUsedQuota(channel)).isZero();
    }

    @Test
    @DisplayName("主渠道发送失败对称释放配额，且原生个性化批量发信受 SmsQuotaGuard 与 Failover 治理")
    void shouldReleaseQuotaOnSendFailureAndEnforceQuotaOnPersonalizedBatch() {
        SmsProperties props = new SmsProperties();
        SmsChannelRegistry registry = new SmsChannelRegistry(props);
        registry.register(SmsChannel.builder()
                .name("pri-fail")
                .supplier(SmsSupplier.MOCK)
                .defaultTemplateId("TPL_1")
                .maxQuota(2)
                .failoverChannel("bak-ok")
                .build());
        registry.register(SmsChannel.builder()
                .name("bak-ok")
                .supplier(SmsSupplier.MOCK)
                .defaultTemplateId("TPL_1")
                .maxQuota(2)
                .build());

        InMemorySmsQuotaGuard quotaGuard = new InMemorySmsQuotaGuard();
        io.github.atengk.boot.sms.provider.SmsProvider provider = new MockSmsProvider(props.getGuard()) {
            @Override
            public SmsResult send(SmsRequest request, SmsChannel channel) {
                if ("pri-fail".equals(channel.getName())) {
                    throw new io.github.atengk.boot.sms.exception.SmsSendException("pri-fail", "ERR", "down", null, "down");
                }
                return super.send(request, channel);
            }

            @Override
            public io.github.atengk.boot.sms.model.BatchSmsResult sendPersonalizedBatch(
                    io.github.atengk.boot.sms.model.BatchSmsRequest request, SmsChannel channel) {
                return io.github.atengk.boot.sms.model.BatchSmsResult.of(
                        request.getPhones().size(), request.getPhones().size(),
                        List.of(), List.of(), List.of(SmsResult.mock(channel.getName(), "BIZ", "REQ"))
                );
            }
        };

        SmsTemplate template = new SmsTemplate(
                props, List.of(provider), registry, null, null, null, null,
                null, null, null, null, null, null, null, null, quotaGuard
        );

        // 1. pri-fail 失败降级至 bak-ok 后，pri-fail 预扣的配额应被对称释放回 0
        SmsResult res = template.send(SmsRequest.of("13800002222", "TPL_1").withChannel("pri-fail"));
        assertThat(res.getChannel()).isEqualTo("bak-ok");
        assertThat(template.getChannelQuotaUsed("pri-fail")).isZero();
        assertThat(template.getChannelQuotaUsed("bak-ok")).isEqualTo(1L);

        // 2. 原生个性化批量发送受 maxQuota=2 约束：首次 2 条超 bak-ok 剩余配额(仅剩1)，直接触发 SmsBudgetExceededException
        io.github.atengk.boot.sms.model.BatchSmsRequest personalized =
                io.github.atengk.boot.sms.model.BatchSmsRequest.ofPersonalized(
                        java.util.Map.of(
                                "13800003331", io.github.atengk.boot.sms.model.SmsParam.of("name", "A"),
                                "13800003332", io.github.atengk.boot.sms.model.SmsParam.of("name", "B")
                        ),
                        "TPL_PERS"
                );
        io.github.atengk.boot.sms.model.BatchSmsRequest persOnBak = io.github.atengk.boot.sms.model.BatchSmsRequest.builder()
                .phones(personalized.getPhones())
                .templateId("TPL_PERS")
                .phoneParams(personalized.getPhoneParams())
                .channel("bak-ok")
                .build();
        assertThatThrownBy(() -> template.sendBatch(persOnBak))
                .isInstanceOf(SmsBudgetExceededException.class);
    }
}
