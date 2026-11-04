package io.github.atengk.boot.ai.quota;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.properties.AtengAiQuotaProperties;
import io.github.atengk.boot.core.context.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SaaS 多租户大模型调用配额风控默认单机内存实现类
 * <p>
 * 基于并发安全无锁容器管理日度消耗计数器，并在跨天时自动原子重置淘汰，保证开发与单机测试零外部依赖自洽。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-04
 */
public class DefaultAiQuotaChecker implements AiQuotaChecker {

    private static final Logger log = LoggerFactory.getLogger(DefaultAiQuotaChecker.class);

    protected static final String DEFAULT_TENANT_ID = "default";

    protected final AtengAiQuotaProperties properties;
    protected final Clock clock;
    protected final ConcurrentMap<String, TenantDailyUsage> memoryUsageMap = new ConcurrentHashMap<>();

    public DefaultAiQuotaChecker(AtengAiQuotaProperties properties) {
        this(properties, Clock.systemDefaultZone());
    }

    public DefaultAiQuotaChecker(AtengAiQuotaProperties properties, Clock clock) {
        this.properties = properties != null ? properties : new AtengAiQuotaProperties();
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
        memoryUsageMap.remove(actualTenantId);
    }

    /**
     * 判断当前是否工作在分布式集群模式
     *
     * @return true 为分布式模式，false 为单机内存模式
     */
    @Override
    public boolean isDistributed() {
        return false;
    }

    /**
     * 判断当前是否工作在 Redis 分布式集群模式 (兼容历史方法)
     *
     * @return true 为 Redis 模式，false 为内存模式
     */
    public boolean isRedisMode() {
        return isDistributed();
    }

    protected long getDailyRequestsInternal(String tenantId, String date) {
        TenantDailyUsage usage = getMemoryUsage(tenantId, date);
        return usage.requests.get();
    }

    protected long getDailyTokensInternal(String tenantId, String date) {
        TenantDailyUsage usage = getMemoryUsage(tenantId, date);
        return usage.tokens.get();
    }

    protected void recordUsageMemory(String tenantId, String date, int totalTokensUsed) {
        TenantDailyUsage usage = getMemoryUsage(tenantId, date);
        usage.requests.incrementAndGet();
        if (totalTokensUsed > 0) {
            usage.tokens.addAndGet(totalTokensUsed);
        }
    }

    protected TenantDailyUsage getMemoryUsage(String tenantId, String date) {
        return memoryUsageMap.compute(tenantId, (k, existing) -> {
            if (existing == null || !date.equals(existing.date)) {
                return new TenantDailyUsage(date);
            }
            return existing;
        });
    }

    protected String resolveTenantId(String tenantId) {
        if (tenantId != null && !tenantId.isBlank()) {
            return tenantId.trim();
        }
        String ctxTenant = TenantContextHolder.getTenantId();
        if (ctxTenant != null && !ctxTenant.isBlank()) {
            return ctxTenant.trim();
        }
        return DEFAULT_TENANT_ID;
    }

    protected String getCurrentDate() {
        return LocalDate.now(clock).toString();
    }

    /**
     * 单机内存并发安全日度消耗容器
     */
    protected static class TenantDailyUsage {
        final String date;
        final AtomicLong requests = new AtomicLong(0);
        final AtomicLong tokens = new AtomicLong(0);

        TenantDailyUsage(String date) {
            this.date = Objects.requireNonNull(date, "date must not be null");
        }
    }
}
