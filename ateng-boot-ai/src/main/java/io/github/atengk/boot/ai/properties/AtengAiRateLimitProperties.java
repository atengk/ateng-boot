package io.github.atengk.boot.ai.properties;

import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 微观突发流量滑动窗口限流器配置属性 (RPS & TPM)
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
public class AtengAiRateLimitProperties {

    /**
     * 是否启用微观突发流量限流保护
     */
    private boolean enabled = true;

    /**
     * 全局默认每秒请求数上限 (Requests Per Second)，<= 0 表示不限制
     */
    private double defaultRps = 0;

    /**
     * 全局默认每分钟 Token 消耗数上限 (Tokens Per Minute)，<= 0 表示不限制
     */
    private long defaultTpm = 0;

    /**
     * 分布式 Redis 限流键前缀
     */
    private String keyPrefix = "ateng:ai:ratelimit:";

    /**
     * 针对特定模型的限流配置拓扑 (key 为 modelName)
     */
    private Map<String, ModelRateLimit> models = new LinkedHashMap<>();

    /**
     * 针对特定租户的限流配置拓扑 (key 为 tenantId)
     */
    private Map<String, TenantRateLimit> tenants = new LinkedHashMap<>();

    /**
     * 单个模型的限流阈值定义
     */
    @Data
    public static class ModelRateLimit {
        /**
         * 模型专属每秒请求数上限 (RPS)，<= 0 表示不限制
         */
        private double rps = 0;

        /**
         * 模型专属每分钟 Token 上限 (TPM)，<= 0 表示不限制
         */
        private long tpm = 0;
    }

    /**
     * 单个租户的限流阈值定义
     */
    @Data
    public static class TenantRateLimit {
        /**
         * 租户专属每秒请求数上限 (RPS)，<= 0 表示不限制
         */
        private double rps = 0;

        /**
         * 租户专属每分钟 Token 上限 (TPM)，<= 0 表示不限制
         */
        private long tpm = 0;

        /**
         * 该租户针对特定模型的细粒度限流覆盖 (key 为 modelName)
         */
        private Map<String, ModelRateLimit> models = new LinkedHashMap<>();
    }
}
