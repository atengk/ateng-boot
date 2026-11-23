package io.github.atengk.boot.search.model;

import io.github.atengk.boot.search.enums.SearchHybridMode;
import io.github.atengk.boot.search.query.SearchQueryBuilder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.function.Consumer;

/**
 * 混合检索 (BM25 词法召回 + kNN 向量召回) 融合重排参数配置
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HybridSearchOptions implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 融合重排模式（默认 {@link SearchHybridMode#CLIENT_RRF}，零付费许可证依赖）
     */
    @Builder.Default
    private SearchHybridMode mode = SearchHybridMode.CLIENT_RRF;

    /**
     * RRF 倒数排名平滑常数（默认 60）
     */
    @Builder.Default
    private int rrfRankConstant = 60;

    /**
     * {@link SearchHybridMode#CLIENT_WEIGHTED} 模式下全文词法得分权重（默认 0.5）
     */
    @Builder.Default
    private double textWeight = 0.5d;

    /**
     * {@link SearchHybridMode#CLIENT_WEIGHTED} 模式下向量相似度得分权重（默认 0.5）
     */
    @Builder.Default
    private double vectorWeight = 0.5d;

    /**
     * 融合后最终返回的最大文档数（默认 10）
     */
    @Builder.Default
    private int topK = 10;

    /**
     * 各召回分支候选窗口大小（为空时默认取 {@code Math.max(topK, k)}）
     */
    private Integer windowSize;

    /**
     * kNN 向量分支可选预过滤条件构建器
     */
    private transient Consumer<SearchQueryBuilder> knnFilter;

    /**
     * 创建默认配置（{@code CLIENT_RRF}, {@code rrfRankConstant=60}, {@code topK=10}）
     *
     * @return 默认 HybridSearchOptions
     */
    public static HybridSearchOptions defaults() {
        return HybridSearchOptions.builder().build();
    }
}
