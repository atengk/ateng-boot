package io.github.atengk.boot.ai.cache;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * AI 语义缓存检索命中结果契约对象
 *
 * @author Ateng
 * @since 2026-11-22
 */
@Getter
@Builder
@AllArgsConstructor
public class AiCacheLookupResult {

    /**
     * 缓存命中层级类型
     */
    public enum MatchType {
        /**
         * 一级精确 Hash 命中
         */
        EXACT,
        /**
         * 二级向量余弦相似度语义命中
         */
        SEMANTIC
    }

    /**
     * 命中的缓存层级类型
     */
    private final MatchType matchType;

    /**
     * 命中的缓存响应正文
     */
    private final String responseText;

    /**
     * 余弦相似度分值（精确命中固定为 1.0）
     */
    private final double similarity;

    /**
     * 命中的精确 Hash 键
     */
    private final String exactKey;

    /**
     * 命中的语义作用域键
     */
    private final String scopeKey;

    /**
     * 归属租户标识
     */
    private final String tenantId;

    /**
     * 归属大模型标识
     */
    private final String modelName;
}
