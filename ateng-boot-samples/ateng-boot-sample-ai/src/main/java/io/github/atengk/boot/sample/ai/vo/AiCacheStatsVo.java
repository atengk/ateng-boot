package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 语义缓存运行态指标视图 VO
 *
 * @author Ateng
 * @since 2026-11-22
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiCacheStatsVo {

    /**
     * 是否已开启语义缓存组件
     */
    private boolean enabled;

    /**
     * 是否开启二级向量语义匹配
     */
    private boolean semanticEnabled;

    /**
     * 底层存储类型（IN_MEMORY / REDIS / DISABLED）
     */
    private String storeType;

    /**
     * 当前活跃缓存条目数量
     */
    private long cacheSize;

    /**
     * 累计缓存命中次数
     */
    private long hitCount;

    /**
     * 累计缓存未命中次数
     */
    private long missCount;
}
