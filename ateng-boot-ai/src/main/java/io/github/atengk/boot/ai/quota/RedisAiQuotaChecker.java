package io.github.atengk.boot.ai.quota;

import io.github.atengk.boot.ai.properties.AtengAiQuotaProperties;
import io.github.atengk.boot.core.context.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;

/**
 * SaaS 多租户大模型调用配额风控 Redis 分布式集群实现类
 * <p>
 * 依托 Redis 原子自增（INCRBY）与天级滑动 TTL（2 天）精准统计租户每日调用量，支持多 Pod 集群共享风控状态。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-04
 */
public class RedisAiQuotaChecker extends DefaultAiQuotaChecker {

    private static final Logger log = LoggerFactory.getLogger(RedisAiQuotaChecker.class);

    private static final Duration REDIS_KEY_TTL = Duration.ofDays(2);

    private final StringRedisTemplate redisTemplate;

    public RedisAiQuotaChecker(AtengAiQuotaProperties properties, StringRedisTemplate redisTemplate) {
        this(properties, redisTemplate, Clock.systemDefaultZone());
    }

    public RedisAiQuotaChecker(AtengAiQuotaProperties properties, StringRedisTemplate redisTemplate, Clock clock) {
        super(properties, clock);
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean isDistributed() {
        return redisTemplate != null;
    }

    @Override
    public boolean isRedisMode() {
        return isDistributed();
    }

    @Override
    public void recordUsage(String tenantId, String modelName, int totalTokensUsed) {
        if (!properties.isEnabled() || TenantContextHolder.isIgnoreTenant()) {
            return;
        }

        String actualTenantId = resolveTenantId(tenantId);
        String date = getCurrentDate();

        if (redisTemplate != null) {
            try {
                recordUsageRedis(actualTenantId, date, totalTokensUsed);
                return;
            } catch (Throwable t) {
                log.warn("Redis 记录租户配额异常，平滑降级至内存计数: {}", t.getMessage());
            }
        }

        super.recordUsage(tenantId, modelName, totalTokensUsed);
    }

    @Override
    public void resetQuota(String tenantId) {
        String actualTenantId = resolveTenantId(tenantId);
        String date = getCurrentDate();

        if (redisTemplate != null) {
            try {
                redisTemplate.delete(buildKey(actualTenantId, date, "requests"));
                redisTemplate.delete(buildKey(actualTenantId, date, "tokens"));
            } catch (Throwable t) {
                log.warn("Redis 重置租户配额异常: {}", t.getMessage());
            }
        }
        super.resetQuota(tenantId);
    }

    @Override
    protected long getDailyRequestsInternal(String tenantId, String date) {
        if (redisTemplate != null) {
            try {
                String val = redisTemplate.opsForValue().get(buildKey(tenantId, date, "requests"));
                return val != null ? Long.parseLong(val) : 0L;
            } catch (Throwable t) {
                log.warn("Redis 读取租户请求次数异常，降级至内存缓存: {}", t.getMessage());
            }
        }
        return super.getDailyRequestsInternal(tenantId, date);
    }

    @Override
    protected long getDailyTokensInternal(String tenantId, String date) {
        if (redisTemplate != null) {
            try {
                String val = redisTemplate.opsForValue().get(buildKey(tenantId, date, "tokens"));
                return val != null ? Long.parseLong(val) : 0L;
            } catch (Throwable t) {
                log.warn("Redis 读取租户 Token 消耗异常，降级至内存缓存: {}", t.getMessage());
            }
        }
        return super.getDailyTokensInternal(tenantId, date);
    }

    private void recordUsageRedis(String tenantId, String date, int totalTokensUsed) {
        String reqKey = buildKey(tenantId, date, "requests");
        Long reqCount = redisTemplate.opsForValue().increment(reqKey, 1);
        ensureTtl(reqKey, reqCount, 1);

        if (totalTokensUsed > 0) {
            String tokenKey = buildKey(tenantId, date, "tokens");
            Long tokenCount = redisTemplate.opsForValue().increment(tokenKey, totalTokensUsed);
            ensureTtl(tokenKey, tokenCount, totalTokensUsed);
        }
    }

    private void ensureTtl(String key, Long currentVal, long expectedFirstIncrement) {
        if (currentVal != null && currentVal == expectedFirstIncrement) {
            redisTemplate.expire(key, REDIS_KEY_TTL);
        } else {
            Long ttl = redisTemplate.getExpire(key);
            if (ttl == null || ttl < 0) {
                redisTemplate.expire(key, REDIS_KEY_TTL);
            }
        }
    }

    private String buildKey(String tenantId, String date, String metricType) {
        String prefix = properties.getKeyPrefix() != null ? properties.getKeyPrefix() : "ateng:ai:quota:";
        return prefix + tenantId + ":" + date + ":" + metricType;
    }
}
