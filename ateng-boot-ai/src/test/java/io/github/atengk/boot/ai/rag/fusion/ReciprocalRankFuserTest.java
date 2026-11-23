package io.github.atengk.boot.ai.rag.fusion;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RRF 倒数排名融合算法器单元测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
@DisplayName("RRF 倒数排名融合算法器单元测试")
class ReciprocalRankFuserTest {

    @Test
    @DisplayName("空输入或非法 Top-K 时返回空列表")
    void testEmptyInputs() {
        assertThat(ReciprocalRankFuser.fuse(null, 5)).isEmpty();
        assertThat(ReciprocalRankFuser.fuse(Collections.emptyList(), 5)).isEmpty();
        assertThat(ReciprocalRankFuser.fuse(List.of(List.of(Content.from("a"))), 0)).isEmpty();
        assertThat(ReciprocalRankFuser.fuse(List.of(List.of(Content.from("a"))), -1)).isEmpty();
    }

    @Test
    @DisplayName("多路检索结果去重与标准 RRF 得分排序融合")
    void testStandardRrfFusion() {
        // 文档 A: 稠密排名 1 (1/61 ≈ 0.01639), 稀疏排名 2 (1/62 ≈ 0.01613) -> 总分 ≈ 0.03252
        // 文档 B: 稠密排名 2 (1/62 ≈ 0.01613), 稀疏未召回 -> 总分 ≈ 0.01613
        // 文档 C: 稠密未召回, 稀疏排名 1 (1/61 ≈ 0.01639) -> 总分 ≈ 0.01639
        TextSegment segA = TextSegment.from("Spring Boot 核心架构", Metadata.from("doc_id", "DOC-A").put("chunk_index", "0"));
        TextSegment segB = TextSegment.from("MyBatis-Plus 持久层", Metadata.from("doc_id", "DOC-B").put("chunk_index", "0"));
        TextSegment segC = TextSegment.from("Redis 缓存防雪崩", Metadata.from("doc_id", "DOC-C").put("chunk_index", "0"));

        List<Content> denseList = List.of(Content.from(segA), Content.from(segB));
        List<Content> sparseList = List.of(Content.from(segC), Content.from(segA));

        List<ReciprocalRankFuser.ScoredContent> scored = ReciprocalRankFuser.fuseWithScores(
                List.of(denseList, sparseList),
                null, // 默认权重 1.0
                3,
                60
        );

        assertThat(scored).hasSize(3);

        // 文档 A 受到两路召回协同放大，综合得分应当稳居第一位
        assertThat(scored.get(0).content().textSegment().text()).isEqualTo("Spring Boot 核心架构");
        double expectedScoreA = (1.0 / 61.0) + (1.0 / 62.0);
        assertThat(scored.get(0).rrfScore()).isCloseTo(expectedScoreA, org.assertj.core.data.Offset.offset(0.0001));

        // 文档 C 得分 (1/61) > 文档 B 得分 (1/62)
        assertThat(scored.get(1).content().textSegment().text()).isEqualTo("Redis 缓存防雪崩");
        assertThat(scored.get(2).content().textSegment().text()).isEqualTo("MyBatis-Plus 持久层");
    }

    @Test
    @DisplayName("加权 RRF 融合能够根据分路权重倾斜排序")
    void testWeightedRrfFusion() {
        TextSegment segDenseOnly = TextSegment.from("稠密专属切片", Metadata.from("doc_id", "DOC-D").put("chunk_index", "0"));
        TextSegment segSparseOnly = TextSegment.from("稀疏专属切片", Metadata.from("doc_id", "DOC-S").put("chunk_index", "0"));

        List<Content> denseList = List.of(Content.from(segDenseOnly));
        List<Content> sparseList = List.of(Content.from(segSparseOnly));

        // 赋予 dense 3.0 倍权重，sparse 0.5 倍权重
        List<Content> fused = ReciprocalRankFuser.fuse(
                List.of(denseList, sparseList),
                List.of(3.0, 0.5),
                2,
                60
        );

        assertThat(fused).hasSize(2);
        assertThat(fused.get(0).textSegment().text()).isEqualTo("稠密专属切片");
        assertThat(fused.get(1).textSegment().text()).isEqualTo("稀疏专属切片");
    }
}
