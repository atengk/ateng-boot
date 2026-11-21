package io.github.atengk.boot.ai.cache;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 双级响应缓存条目持久化模型
 *
 * @author Ateng
 * @since 2026-11-22
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiCacheEntry {

    /**
     * 复合防串话精确 Hash 键（绑定租户、模型、采样指纹、系统提示词摘要与消息载荷）
     */
    private String exactKey;

    /**
     * 语义检索隔离作用域键（绑定租户、模型、采样指纹与系统提示词摘要，不含用户输入文本）
     */
    private String scopeKey;

    /**
     * 归属租户标识
     */
    private String tenantId;

    /**
     * 归属大模型标识
     */
    private String modelName;

    /**
     * 归一化后的用户查询文本（用于向量语义匹配与审计定位）
     */
    private String queryText;

    /**
     * 缓存的合规模型响应正文
     */
    private String responseText;

    /**
     * 可选的用户查询稠密特征向量（开启语义缓存且存在 EmbeddingModel 时填充）
     */
    private float[] embedding;

    /**
     * 写入时间戳（毫秒）
     */
    private long createdAtEpochMs;

    /**
     * 绝对过期时间戳（毫秒，<= 0 表示永不过期）
     */
    private long expiresAtEpochMs;

    /**
     * 判断当前缓存条目在指定时间点是否已过期
     *
     * @param nowEpochMs 当前时间戳（毫秒）
     * @return 若已过期返回 true，否则返回 false
     */
    public boolean isExpired(long nowEpochMs) {
        return expiresAtEpochMs > 0 && expiresAtEpochMs <= nowEpochMs;
    }
}
