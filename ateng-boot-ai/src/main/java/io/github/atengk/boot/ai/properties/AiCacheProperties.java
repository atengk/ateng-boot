package io.github.atengk.boot.ai.properties;

import lombok.Data;

import java.time.Duration;

/**
 * AI 精确与向量语义双级响应缓存配置属性
 * <p>
 * 挂载于 {@code ateng.boot.ai.cache.*}，用于降低高频相似或重复大模型调用延迟并节省调用配额成本。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-22
 */
@Data
public class AiCacheProperties {

    /**
     * 缓存存储介质类型
     */
    public enum CacheType {
        /**
         * 自动探测：存在 StringRedisTemplate 时启用 Redis 分布式缓存，否则平滑降级单机内存 LRU 缓存
         */
        AUTO,
        /**
         * 强制使用基于 ReentrantLock 的单机有界 LRU 内存缓存
         */
        IN_MEMORY,
        /**
         * 强制使用基于 StringRedisTemplate 的分布式缓存
         */
        REDIS
    }

    /**
     * 是否启用 AI 语义与精确双级响应缓存（默认关闭，按需显式开启）
     */
    private boolean enabled = false;

    /**
     * 缓存底层存储类型（AUTO / IN_MEMORY / REDIS）
     */
    private CacheType type = CacheType.AUTO;

    /**
     * 是否启用精确 Hash 匹配一级缓存
     */
    private boolean exactEnabled = true;

    /**
     * 是否启用向量余弦相似度语义匹配二级缓存（开启且存在 EmbeddingModel 时生效）
     */
    private boolean semanticEnabled = false;

    /**
     * 语义相似度命中阈值（0.0 ~ 1.0，默认 0.95）
     */
    private double similarityThreshold = 0.95;

    /**
     * 缓存条目过期时长（默认 1 小时）
     */
    private Duration ttl = Duration.ofHours(1);

    /**
     * 内存模式及单索引最大缓存条目上限（超出触发 LRU 淘汰，默认 1000）
     */
    private int maxEntries = 1000;

    /**
     * Redis 分布式缓存物理键前缀
     */
    private String keyPrefix = "ateng:ai:cache:";

    /**
     * 允许参与缓存的最大模型采样温度阈值（temperature >= 该值时认为属于高随机创意生成，自动跳过缓存）
     */
    private double maxCacheableTemperature = 0.8;

    /**
     * 是否允许缓存检出 PII 敏感信息（手机号/身份证/银行卡/邮箱/API Key）的请求与响应（默认 false 防隐私跨会话残留）
     */
    private boolean cachePii = false;
}
