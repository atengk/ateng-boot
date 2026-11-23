package io.github.atengk.boot.ai.rag.rerank;

import dev.langchain4j.data.segment.TextSegment;

import java.util.Collections;
import java.util.List;

/**
 * 文档检索交叉重排模型 SPI 顶层接口
 * <p>
 * 对向量粗排召回的候选文本切片集合进行 Cross-Encoder 交叉注意力二次精排计算，
 * 评估各候选片段相对于查询 query 的语义相关度得分，并按相关度由高到低重新排序返回。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public interface Reranker {

    /**
     * 对候选切片进行打分与重排
     *
     * @param query    查询关键词或问题
     * @param segments 粗排召回的候选文本片段列表
     * @param topK     精排保留的最大结果数 (必须 > 0)
     * @return 经过重排并截取 topK 的结果列表（按相关度得分降序排序）
     */
    List<RerankResult> rerank(String query, List<TextSegment> segments, int topK);

    /**
     * 对候选切片进行打分与全量重排 (不截断数量)
     *
     * @param query    查询关键词或问题
     * @param segments 粗排召回的候选文本片段列表
     * @return 经过重排的全量结果列表（按相关度得分降序排序）
     */
    default List<RerankResult> rerank(String query, List<TextSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return Collections.emptyList();
        }
        return rerank(query, segments, segments.size());
    }
}
