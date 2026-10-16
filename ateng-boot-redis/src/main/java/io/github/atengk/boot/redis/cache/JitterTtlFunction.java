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

    private final Duration baseTtl;
    private final Duration maxJitter;
    private final boolean jitterEnabled;

    public JitterTtlFunction(Duration baseTtl, Duration maxJitter, boolean jitterEnabled) {
        this.baseTtl = baseTtl != null ? baseTtl : Duration.ofMinutes(30);
        this.maxJitter = maxJitter != null ? maxJitter : Duration.ofSeconds(60);
        this.jitterEnabled = jitterEnabled;
    }

    @Override
    public Duration getTimeToLive(Object key, Object value) {
        if (!jitterEnabled || maxJitter == null || maxJitter.isZero() || maxJitter.isNegative()
                || baseTtl == null || baseTtl.isZero() || baseTtl.isNegative()) {
            return baseTtl;
        }
        long jitterMillis = ThreadLocalRandom.current().nextLong(1, Math.max(2, maxJitter.toMillis() + 1));
        return baseTtl.plusMillis(jitterMillis);
    }

    public Duration getBaseTtl() {
        return baseTtl;
    }

    public Duration getMaxJitter() {
        return maxJitter;
    }

    public boolean isJitterEnabled() {
        return jitterEnabled;
    }
}
