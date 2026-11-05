package io.github.atengk.boot.ai.rag.rerank;

import dev.langchain4j.data.segment.TextSegment;

/**
 * 检索交叉重排结果包装对象
 *
 * @param segment 重排后的文本片段
 * @param index   在原始粗排候选列表中的索引下标
 * @param score   交叉注意力相关度得分
 * @author Ateng
 * @since 2026-11-05
 */
public record RerankResult(TextSegment segment, int index, Double score) implements Comparable<RerankResult> {

    @Override
    public int compareTo(RerankResult other) {
        if (other == null || other.score == null) {
            return this.score == null ? 0 : -1;
        }
        if (this.score == null) {
            return 1;
        }
        // 降序排序 (相关度得分高者排在前面)
        return Double.compare(other.score, this.score);
    }
}
