package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 检索候选两阶段重排响应视图对象
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRagRerankVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 查询关键词
     */
    private String query;

    /**
     * 实际返回 Top-K 数量
     */
    private int topK;

    /**
     * 重排后得分最高的候选列表
     */
    @Builder.Default
    private List<RankedItem> results = Collections.emptyList();

    /**
     * 单个排序结果项
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RankedItem implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * 重排后的名次 (从 1 开始)
         */
        private int rank;

        /**
         * 原始列表中的索引位置 (从 0 开始)
         */
        private int originalIndex;

        /**
         * 交叉重排相似度/语义得分
         */
        private double score;

        /**
         * 候选文本内容
         */
        private String text;
    }
}
