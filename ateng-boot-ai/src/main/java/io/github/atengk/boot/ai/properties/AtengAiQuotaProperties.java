package io.github.atengk.boot.ai.properties;

import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 大模型调用配额与风控强类型配置属性
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
public class AtengAiQuotaProperties {

    /**
     * 是否启用租户与用户级大模型调用配额风控
     */
    private boolean enabled = true;

    /**
     * 单租户单日大模型调用次数上限（<= 0 表示不限制）
     */
    private long dailyRequestLimit = 0;

    /**
     * 单租户单日大模型 Token 累计消耗上限（<= 0 表示不限制）
     */
    private long dailyTokenLimit = 0;

    /**
     * Redis 分布式滑动窗口计数键前缀
     */
    private String keyPrefix = "ateng:ai:quota:";

    /**
     * 针对特定租户定制的单日调用次数上限映射（key 为 tenantId，value 为调用次数上限）
     */
    private Map<String, Long> tenantRequestLimits = new LinkedHashMap<>();

    /**
     * 针对特定租户定制的单日 Token 消耗上限映射（key 为 tenantId，value 为 Token 消耗上限）
     */
    private Map<String, Long> tenantTokenLimits = new LinkedHashMap<>();

    // 兼容历史与文档配置别名方法

    public void setDefaultDailyLimit(long limit) {
        this.dailyRequestLimit = limit;
    }

    public long getDefaultDailyLimit() {
        return this.dailyRequestLimit;
    }

    public void setDefaultDailyTokenLimit(long limit) {
        this.dailyTokenLimit = limit;
    }

    public long getDefaultDailyTokenLimit() {
        return this.dailyTokenLimit;
    }

    public void setTenantDailyLimits(Map<String, Long> limits) {
        if (limits != null) {
            this.tenantRequestLimits.putAll(limits);
        }
    }

    public void setTenantDailyTokenLimits(Map<String, Long> limits) {
        if (limits != null) {
            this.tenantTokenLimits.putAll(limits);
        }
    }

    /**
     * 解析指定租户应生效的单日调用次数上限
     *
     * @param tenantId 租户标识
     * @return 该租户适用的调用次数上限
     */
    public long resolveDailyRequestLimit(String tenantId) {
        if (tenantId != null && !tenantId.isBlank() && tenantRequestLimits != null) {
            Long limit = tenantRequestLimits.get(tenantId.trim());
            if (limit != null) {
                return limit;
            }
        }
        return dailyRequestLimit;
    }

    /**
     * 解析指定租户应生效的单日 Token 消耗上限
     *
     * @param tenantId 租户标识
     * @return 该租户适用的 Token 消耗上限
     */
    public long resolveDailyTokenLimit(String tenantId) {
        if (tenantId != null && !tenantId.isBlank() && tenantTokenLimits != null) {
            Long limit = tenantTokenLimits.get(tenantId.trim());
            if (limit != null) {
                return limit;
            }
        }
        return dailyTokenLimit;
    }

    public Map<String, Long> getTenantRequestLimits() {
        if (tenantRequestLimits == null || tenantRequestLimits.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(tenantRequestLimits);
    }

    public Map<String, Long> getTenantTokenLimits() {
        if (tenantTokenLimits == null || tenantTokenLimits.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(tenantTokenLimits);
    }
}
