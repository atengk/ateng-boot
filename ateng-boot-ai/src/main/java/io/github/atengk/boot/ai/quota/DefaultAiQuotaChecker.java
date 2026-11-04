package io.github.atengk.boot.ai.quota;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.properties.AtengAiQuotaProperties;
import io.github.atengk.boot.core.context.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SaaS 多租户大模型调用配额风控默认实现类
 * <p>
 * 采用双模自适应存储架构：
 * 1. 分布式 Redis 模式（当容器存在 StringRedisTemplate 时自动激活）：
 *    依托 Redis 原子自增（INCRBY）与天级滑动 TTL（2 天）精准统计租户每日调用量，支持多 Pod 集群共享风控状态；
 * 2. 单机内存模式（无 Redis 时保底自洽）：
 *    基于并发安全无锁容器管理日度消耗计数器，并在跨天时自动原子重置淘汰，保证开发与单机测试零外部依赖；
 * 3. 熔断判定：
 *    在调用前置拦截点对比配置的 daily-request-limit 与 daily-token-limit，超额即刻 Fail-Fast 阻断并抛出
 *    {@link AiResultCode#AI_QUOTA_EXCEEDED} 业务异常。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-04
 */
public class DefaultAiQuotaChecker implements AiQuotaChecker {

    private static final Logger log = LoggerFactory.getLogger(DefaultAiQuotaChecker.class);

    private static final String DEFAULT_TENANT_ID = "default";
    private static final Duration REDIS_KEY_TTL = Duration.ofDays(2);

    private final AtengAiQuotaProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final ConcurrentMap<String, TenantDailyUsage> memoryUsageMap = new ConcurrentHashMap<>();

    public DefaultAiQuotaChecker(AtengAiQuotaProperties properties) {
        this(properties, null, Clock.systemDefaultZone());
    }

    public DefaultAiQuotaChecker(AtengAiQuotaProperties properties, StringRedisTemplate redisTemplate) {
        this(properties, redisTemplate, Clock.systemDefaultZone());
    }

    public DefaultAiQuotaChecker(AtengAiQuotaProperties properties, StringRedisTemplate redisTemplate, Clock clock) {
        this.properties = properties != null ? properties : new AtengAiQuotaProperties();
        this.redisTemplate = redisTemplate;
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
    }

    @Override
    public void checkQuota(String tenantId, String modelName, int promptTokens) {
        if (!properties.isEnabled() || TenantContextHolder.isIgnoreTenant()) {
            return;
        }

        String actualTenantId = resolveTenantId(tenantId);
        long requestLimit = properties.resolveDailyRequestLimit(actualTenantId);
        long tokenLimit = properties.resolveDailyTokenLimit(actualTenantId);

        if (requestLimit <= 0 && tokenLimit <= 0) {
            return;
        }

        String date = getCurrentDate();
        long currentRequests = getDailyRequestsInternal(actualTenantId, date);
        long currentTokens = getDailyTokensInternal(actualTenantId, date);

        // 1. 调用次数上限校验
        if (requestLimit > 0 && currentRequests >= requestLimit) {
            log.warn("[AI-QUOTA-BLOCKED] 租户 [{}] 今日 AI 调用次数超限: 当前 {} 次, 上限 {} 次, 触发熔断阻断",
                    actualTenantId, currentRequests, requestLimit);
            throw new AiException(AiResultCode.AI_QUOTA_EXCEEDED,
                    String.format("租户 [%s] 今日 AI 调用次数已达上限 (%d/%d 次)", actualTenantId, currentRequests, requestLimit));
        }

        // 2. Token 消耗上限校验 (结合本次预估输入 Token)
        if (tokenLimit > 0 && (currentTokens + promptTokens) > tokenLimit) {
            log.warn("[AI-QUOTA-BLOCKED] 租户 [{}] 今日 AI Token 消耗超限: 当前 {} Tokens, 预估需要 {} Tokens, 上限 {} Tokens, 触发熔断阻断",
                    actualTenantId, currentTokens, promptTokens, tokenLimit);
            throw new AiException(AiResultCode.AI_QUOTA_EXCEEDED,
                    String.format("租户 [%s] 今日 AI Token 消耗已达上限 (%d/%d Tokens，本次预估需要 %d Tokens)",
                            actualTenantId, currentTokens, tokenLimit, promptTokens));
        }
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

        recordUsageMemory(actualTenantId, date, totalTokensUsed);
    }

    /**
     * 获取指定租户今日累计调用次数
     *
     * @param tenantId 租户标识
     * @return 今日累计调用次数
     */
    public long getDailyRequests(String tenantId) {
        String actualTenantId = resolveTenantId(tenantId);
        return getDailyRequestsInternal(actualTenantId, getCurrentDate());
    }

    /**
     * 获取指定租户今日累计 Token 消耗量
     *
     * @param tenantId 租户标识
     * @return 今日累计 Token 消耗量
     */
    public long getDailyTokens(String tenantId) {
        String actualTenantId = resolveTenantId(tenantId);
        return getDailyTokensInternal(actualTenantId, getCurrentDate());
    }

    /**
     * 重置指定租户今日配额计数
     *
     * @param tenantId 租户标识
     */
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
        memoryUsageMap.remove(actualTenantId);
    }

    /**
     * 判断当前是否工作在 Redis 分布式集群模式
     *
     * @return true 为 Redis 模式，false 为内存模式
     */
    public boolean isRedisMode() {
        return redisTemplate != null;
    }

    private long getDailyRequestsInternal(String tenantId, String date) {
        if (redisTemplate != null) {
            try {
                String val = redisTemplate.opsForValue().get(buildKey(tenantId, date, "requests"));
                return val != null ? Long.parseLong(val) : 0L;
            } catch (Throwable t) {
                log.warn("Redis 读取租户请求次数异常，降级至内存缓存: {}", t.getMessage());
            }
        }
        TenantDailyUsage usage = getMemoryUsage(tenantId, date);
        return usage.requests.get();
    }

    private long getDailyTokensInternal(String tenantId, String date) {
        if (redisTemplate != null) {
            try {
                String val = redisTemplate.opsForValue().get(buildKey(tenantId, date, "tokens"));
                return val != null ? Long.parseLong(val) : 0L;
            } catch (Throwable t) {
                log.warn("Redis 读取租户 Token 消耗异常，降级至内存缓存: {}", t.getMessage());
            }
        }
        TenantDailyUsage usage = getMemoryUsage(tenantId, date);
        return usage.tokens.get();
    }

    private void recordUsageRedis(String tenantId, String date, int totalTokensUsed) {
        String reqKey = buildKey(tenantId, date, "requests");
        Long reqCount = redisTemplate.opsForValue().increment(reqKey, 1);
        if (reqCount != null && reqCount == 1) {
            redisTemplate.expire(reqKey, REDIS_KEY_TTL);
        }

        if (totalTokensUsed > 0) {
            String tokenKey = buildKey(tenantId, date, "tokens");
            Long tokenCount = redisTemplate.opsForValue().increment(tokenKey, totalTokensUsed);
            if (tokenCount != null && tokenCount == totalTokensUsed) {
                redisTemplate.expire(tokenKey, REDIS_KEY_TTL);
            }
        }
    }

    private void recordUsageMemory(String tenantId, String date, int totalTokensUsed) {
        TenantDailyUsage usage = getMemoryUsage(tenantId, date);
        usage.requests.incrementAndGet();
        if (totalTokensUsed > 0) {
            usage.tokens.addAndGet(totalTokensUsed);
        }
    }

    private TenantDailyUsage getMemoryUsage(String tenantId, String date) {
        return memoryUsageMap.compute(tenantId, (k, existing) -> {
            if (existing == null || !date.equals(existing.date)) {
                return new TenantDailyUsage(date);
            }
            return existing;
        });
    }

    private String resolveTenantId(String tenantId) {
        if (tenantId != null && !tenantId.isBlank()) {
            return tenantId.trim();
        }
        String ctxTenant = TenantContextHolder.getTenantId();
        if (ctxTenant != null && !ctxTenant.isBlank()) {
            return ctxTenant.trim();
        }
        return DEFAULT_TENANT_ID;
    }

    private String getCurrentDate() {
        return LocalDate.now(clock).toString();
    }

    private String buildKey(String tenantId, String date, String metricType) {
        String prefix = properties.getKeyPrefix() != null ? properties.getKeyPrefix() : "ateng:ai:quota:";
        return prefix + tenantId + ":" + date + ":" + metricType;
    }

    /**
     * 单机内存并发安全日度消耗容器
     */
    static class TenantDailyUsage {
        final String date;
        final AtomicLong requests = new AtomicLong(0);
        final AtomicLong tokens = new AtomicLong(0);

        TenantDailyUsage(String date) {
            this.date = Objects.requireNonNull(date, "date must not be null");
        }
    }
}
