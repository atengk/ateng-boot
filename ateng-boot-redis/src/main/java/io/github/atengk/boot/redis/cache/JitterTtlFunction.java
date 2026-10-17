package io.github.atengk.boot.redis.cache;

import org.springframework.data.redis.cache.RedisCacheWriter;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 带有随机抖动的 TTL 计算函数
 * 基于 Spring Data Redis 原生 TtlFunction 规范实现，在基准 TTL 之上附加动态随机微抖动，
 * 彻底粉碎大批量缓存键在同一秒同时到期引发的缓存雪崩
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class JitterTtlFunction implements RedisCacheWriter.TtlFunction {

    public static final Duration DEFAULT_NULL_TTL = Duration.ofMinutes(5);

    private final Duration baseTtl;
    private final Duration nullTtl;
    private final Duration maxJitter;
    private final boolean jitterEnabled;

    public JitterTtlFunction(Duration baseTtl, Duration maxJitter, boolean jitterEnabled) {
        this(baseTtl, DEFAULT_NULL_TTL, maxJitter, jitterEnabled);
    }

    public JitterTtlFunction(Duration baseTtl, Duration nullTtl, Duration maxJitter, boolean jitterEnabled) {
        this.baseTtl = baseTtl != null ? baseTtl : Duration.ofMinutes(30);
        this.nullTtl = nullTtl != null ? nullTtl : DEFAULT_NULL_TTL;
        this.maxJitter = maxJitter != null ? maxJitter : Duration.ofSeconds(60);
        this.jitterEnabled = jitterEnabled;
    }

    @Override
    public Duration getTimeToLive(Object key, Object value) {
        // 1. 若当前缓存值为空或 Spring Cache 的空值占位符 NullValue，则分流使用专门的短 TTL 防穿透
        boolean isNullValue = (value == null || value instanceof org.springframework.cache.support.NullValue || "NullValue".equals(value.getClass().getSimpleName()));
        Duration targetTtl = isNullValue ? nullTtl : baseTtl;

        if (!jitterEnabled || maxJitter == null || maxJitter.isZero() || maxJitter.isNegative()
                || targetTtl == null || targetTtl.isZero() || targetTtl.isNegative()) {
            return targetTtl;
        }
        long jitterMillis = ThreadLocalRandom.current().nextLong(1, Math.max(2, maxJitter.toMillis() + 1));
        return targetTtl.plusMillis(jitterMillis);
    }

    public Duration getBaseTtl() {
        return baseTtl;
    }

    public Duration getNullTtl() {
        return nullTtl;
    }

    public Duration getMaxJitter() {
        return maxJitter;
    }

    public boolean isJitterEnabled() {
        return jitterEnabled;
    }
}
