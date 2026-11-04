package io.github.atengk.boot.ai.quota;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.properties.AtengAiQuotaProperties;
import io.github.atengk.boot.core.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SaaS 多租户配额风控检查器双模自适应单元测试
 *
 * @author Ateng
 * @since 2026-11-04
 */
class DefaultAiQuotaCheckerTest {

    private AtengAiQuotaProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AtengAiQuotaProperties();
        properties.setEnabled(true);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("配额开关禁用时所有检查与累计记录均静默跳过")
    void testQuotaDisabled() {
        properties.setEnabled(false);
        properties.setDailyRequestLimit(1);
        DefaultAiQuotaChecker checker = new DefaultAiQuotaChecker(properties);

        assertThat(checker.isRedisMode()).isFalse();

        // 即使连续记录多次也不触发超额熔断
        checker.recordUsage("tenant-01", "model", 100);
        checker.recordUsage("tenant-01", "model", 100);

        assertThatCode(() -> checker.checkQuota("tenant-01", "model", 10))
                .doesNotThrowAnyException();
        assertThat(checker.getDailyRequests("tenant-01")).isEqualTo(0);
        assertThat(checker.getDailyTokens("tenant-01")).isEqualTo(0);
    }

    @Test
    @DisplayName("租户上下文标记 isIgnoreTenant 时自动绕过配额风控检查")
    void testIgnoreTenantBypass() {
        properties.setDailyRequestLimit(1);
        DefaultAiQuotaChecker checker = new DefaultAiQuotaChecker(properties);

        checker.recordUsage("tenant-01", "model", 100);

        // 正常情况下再次调用会触发超额
        assertThatThrownBy(() -> checker.checkQuota("tenant-01", "model", 10))
                .isInstanceOf(AiException.class)
                .hasFieldOrPropertyWithValue("code", AiResultCode.AI_QUOTA_EXCEEDED.getCode());

        // 开启忽略租户后绕过检查
        TenantContextHolder.setIgnoreTenant(true);
        assertThatCode(() -> checker.checkQuota("tenant-01", "model", 10))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("单机内存模式：单日调用次数超额时即刻 Fail-Fast 阻断")
    void testMemoryDailyRequestLimitExceeded() {
        properties.setDailyRequestLimit(2);
        DefaultAiQuotaChecker checker = new DefaultAiQuotaChecker(properties);

        // 第 1 次调用通过并记录
        assertThatCode(() -> checker.checkQuota("tenant-01", "model", 10)).doesNotThrowAnyException();
        checker.recordUsage("tenant-01", "model", 50);

        // 第 2 次调用通过并记录
        assertThatCode(() -> checker.checkQuota("tenant-01", "model", 10)).doesNotThrowAnyException();
        checker.recordUsage("tenant-01", "model", 50);

        assertThat(checker.getDailyRequests("tenant-01")).isEqualTo(2);

        // 第 3 次调用应被即刻阻断
        assertThatThrownBy(() -> checker.checkQuota("tenant-01", "model", 10))
                .isInstanceOf(AiException.class)
                .hasFieldOrPropertyWithValue("code", AiResultCode.AI_QUOTA_EXCEEDED.getCode())
                .hasMessageContaining("今日 AI 调用次数已达上限 (2/2 次)");
    }

    @Test
    @DisplayName("单机内存模式：单日 Token 消耗超额时即刻 Fail-Fast 阻断")
    void testMemoryDailyTokenLimitExceeded() {
        properties.setDailyTokenLimit(100);
        DefaultAiQuotaChecker checker = new DefaultAiQuotaChecker(properties);

        // 记录已消耗 70 tokens
        checker.recordUsage("tenant-01", "model", 70);
        assertThat(checker.getDailyTokens("tenant-01")).isEqualTo(70);

        // 本次预计需要 20 tokens (70 + 20 <= 100) -> 允许通过
        assertThatCode(() -> checker.checkQuota("tenant-01", "model", 20)).doesNotThrowAnyException();

        // 本次预计需要 40 tokens (70 + 40 = 110 > 100) -> 触发熔断
        assertThatThrownBy(() -> checker.checkQuota("tenant-01", "model", 40))
                .isInstanceOf(AiException.class)
                .hasFieldOrPropertyWithValue("code", AiResultCode.AI_QUOTA_EXCEEDED.getCode())
                .hasMessageContaining("今日 AI Token 消耗已达上限 (70/100 Tokens，本次预估需要 40 Tokens)");
    }

    @Test
    @DisplayName("特定租户个性化额度定制高于全局默认配置")
    void testCustomTenantLimits() {
        properties.setDailyRequestLimit(1);
        properties.setTenantRequestLimits(Map.of("tenant-vip", 10L));
        DefaultAiQuotaChecker checker = new DefaultAiQuotaChecker(properties);

        // 普通租户在第 1 次调用后即超限
        checker.recordUsage("tenant-standard", "model", 10);
        assertThatThrownBy(() -> checker.checkQuota("tenant-standard", "model", 10))
                .isInstanceOf(AiException.class)
                .hasMessageContaining("已达上限 (1/1 次)");

        // VIP 租户上限为 10，多次调用仍可继续放行
        for (int i = 0; i < 5; i++) {
            checker.recordUsage("tenant-vip", "model", 10);
        }
        assertThatCode(() -> checker.checkQuota("tenant-vip", "model", 10)).doesNotThrowAnyException();
        assertThat(checker.getDailyRequests("tenant-vip")).isEqualTo(5);
    }

    @Test
    @DisplayName("单机内存模式：跨天滑动窗口自动清理与原子计数淘汰")
    void testMemoryDayRollover() {
        properties.setDailyRequestLimit(1);

        Instant day1 = Instant.parse("2026-11-04T10:00:00Z");
        Instant day2 = Instant.parse("2026-11-05T10:00:00Z");

        MutableClock clock = new MutableClock(day1, ZoneId.of("UTC"));
        DefaultAiQuotaChecker checker = new DefaultAiQuotaChecker(properties, clock);

        // Day 1: 消耗完配额
        checker.recordUsage("tenant-01", "model", 10);
        assertThatThrownBy(() -> checker.checkQuota("tenant-01", "model", 10))
                .isInstanceOf(AiException.class);

        // 跨越至 Day 2: 自动归零放行
        clock.setInstant(day2);
        assertThat(checker.getDailyRequests("tenant-01")).isEqualTo(0);
        assertThatCode(() -> checker.checkQuota("tenant-01", "model", 10)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("测试手动重置租户当日额度计数")
    void testResetQuota() {
        properties.setDailyRequestLimit(1);
        DefaultAiQuotaChecker checker = new DefaultAiQuotaChecker(properties);

        checker.recordUsage("tenant-01", "model", 50);
        assertThatThrownBy(() -> checker.checkQuota("tenant-01", "model", 10)).isInstanceOf(AiException.class);

        // 重置后立即恢复可用
        checker.resetQuota("tenant-01");
        assertThat(checker.getDailyRequests("tenant-01")).isEqualTo(0);
        assertThatCode(() -> checker.checkQuota("tenant-01", "model", 10)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Redis 分布式模式：原子自增、TTL 自动延期与超额熔断")
    void testRedisMode() {
        properties.setDailyRequestLimit(2);
        properties.setDailyTokenLimit(500);

        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        DefaultAiQuotaChecker checker = new RedisAiQuotaChecker(properties, redisTemplate);
        assertThat(checker.isRedisMode()).isTrue();

        // 模拟 Redis 当前返回调用次数 2 次
        when(valueOps.get(org.mockito.ArgumentMatchers.contains(":requests"))).thenReturn("2");
        when(valueOps.get(org.mockito.ArgumentMatchers.contains(":tokens"))).thenReturn("100");

        assertThatThrownBy(() -> checker.checkQuota("tenant-01", "gpt-4o", 20))
                .isInstanceOf(AiException.class)
                .hasFieldOrPropertyWithValue("code", AiResultCode.AI_QUOTA_EXCEEDED.getCode())
                .hasMessageContaining("今日 AI 调用次数已达上限 (2/2 次)");

        // 模拟记录消耗并触发 TTL 设置
        when(valueOps.increment(org.mockito.ArgumentMatchers.contains(":requests"), eq(1L))).thenReturn(1L);
        when(valueOps.increment(org.mockito.ArgumentMatchers.contains(":tokens"), eq(50L))).thenReturn(50L);

        checker.recordUsage("tenant-01", "gpt-4o", 50);

        verify(redisTemplate, Mockito.atLeastOnce()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("Redis 异常故障时平滑降级至单机内存计数，保障链路高可用")
    void testRedisFailureGracefulDegradation() {
        properties.setDailyRequestLimit(1);

        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis connection refused"));

        DefaultAiQuotaChecker checker = new RedisAiQuotaChecker(properties, redisTemplate);

        // Redis 异常降级至内存计数，依然成功完成记录并在第 2 次触发超额
        checker.recordUsage("tenant-01", "model", 10);
        assertThatThrownBy(() -> checker.checkQuota("tenant-01", "model", 10))
                .isInstanceOf(AiException.class)
                .hasFieldOrPropertyWithValue("code", AiResultCode.AI_QUOTA_EXCEEDED.getCode());
    }

    static class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
