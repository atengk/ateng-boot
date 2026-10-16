package io.github.atengk.boot.redis.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 随机抖动 TTL 函数单元测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("JitterTtlFunction 单元测试")
class JitterTtlFunctionTest {

    @Test
    @DisplayName("当禁用抖动时，返回精确基准 TTL")
    void testJitterDisabled() {
        Duration baseTtl = Duration.ofMinutes(10);
        JitterTtlFunction function = new JitterTtlFunction(baseTtl, Duration.ofSeconds(30), false);

        Duration ttl = function.getTimeToLive("key1", "val1");
        assertThat(ttl).isEqualTo(baseTtl);
        assertThat(function.isJitterEnabled()).isFalse();
        assertThat(function.getBaseTtl()).isEqualTo(baseTtl);
        assertThat(function.getMaxJitter()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("当启用抖动时，返回在基准 TTL 上随机上浮后的 TTL")
    void testJitterEnabled() {
        Duration baseTtl = Duration.ofMinutes(10);
        Duration maxJitter = Duration.ofSeconds(60);
        JitterTtlFunction function = new JitterTtlFunction(baseTtl, maxJitter, true);

        for (int i = 0; i < 20; i++) {
            Duration ttl = function.getTimeToLive("key" + i, "val" + i);
            assertThat(ttl.toMillis()).isGreaterThan(baseTtl.toMillis());
            assertThat(ttl.toMillis()).isLessThanOrEqualTo(baseTtl.plus(maxJitter).toMillis() + 100);
        }
    }

    @Test
    @DisplayName("当基准 TTL 为空或零时，直接返回基准 TTL 不增加抖动")
    void testZeroOrNullBaseTtl() {
        JitterTtlFunction functionZero = new JitterTtlFunction(Duration.ZERO, Duration.ofSeconds(30), true);
        assertThat(functionZero.getTimeToLive("key", "val")).isEqualTo(Duration.ZERO);

        JitterTtlFunction functionNegative = new JitterTtlFunction(Duration.ofSeconds(-1), Duration.ofSeconds(30), true);
        assertThat(functionNegative.getTimeToLive("key", "val")).isEqualTo(Duration.ofSeconds(-1));
    }

    @Test
    @DisplayName("当缓存值为空或 NullValue 时，自动分流使用短 nullTtl 防穿透")
    void testNullValueCachingShortTtl() {
        Duration baseTtl = Duration.ofHours(2);
        Duration nullTtl = Duration.ofMinutes(5);
        Duration maxJitter = Duration.ofSeconds(30);
        JitterTtlFunction function = new JitterTtlFunction(baseTtl, nullTtl, maxJitter, false);

        // 1. null 值
        Duration ttlForNull = function.getTimeToLive("key_null", null);
        assertThat(ttlForNull).isEqualTo(nullTtl);

        // 2. 正常非空业务对象返回 baseTtl
        Duration ttlForNormal = function.getTimeToLive("key_normal", "normal_data");
        assertThat(ttlForNormal).isEqualTo(baseTtl);
    }
}
