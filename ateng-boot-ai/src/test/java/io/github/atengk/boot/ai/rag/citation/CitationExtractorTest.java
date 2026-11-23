package io.github.atengk.boot.ai.rag.citation;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 证据溯源与引用清洗器单元测试
 *
 * @author Ateng
 * @since 2026-11-06
 */
class CitationExtractorTest {

    @Test
    @DisplayName("正常场景：精准识别单序号与复合序号并构建结构化 Citation")
    void testNormalExtraction() {
        TextSegment seg1 = TextSegment.from("ateng-boot 遵循纯原生零臃肿依赖设计理念",
                Metadata.from(Map.of("file_name", "architecture.md", "id", "seg-001", "score", 0.95)));
        TextSegment seg2 = TextSegment.from("传输层控制器统一显式返回强类型 Result 契约",
                Metadata.from(Map.of("doc_name", "standard.md", "segment_id", "seg-002", "similarity_score", 0.88)));
        List<TextSegment> candidates = Arrays.asList(seg1, seg2);

        String rawText = "ateng-boot 坚持纯原生设计[1]。同时在传输边界统一返回 Result[2]，且全面兼容标准契约[1, 2]。";

        RagResponse<String> response = CitationExtractor.extract(rawText, candidates);

        assertThat(response).isNotNull();
        assertThat(response.getResult()).isEqualTo(rawText);
        assertThat(response.getGroundingScore()).isEqualTo(1.0);
        assertThat(response.getCitations()).hasSize(2);

        Citation citation1 = response.getCitations().get(0);
        assertThat(citation1.getIndex()).isEqualTo(1);
        assertThat(citation1.getSegmentId()).isEqualTo("seg-001");
        assertThat(citation1.getDocName()).isEqualTo("architecture.md");
        assertThat(citation1.getChunkContent()).contains("纯原生零臃肿");
        assertThat(citation1.getSimilarityScore()).isEqualTo(0.95);

        Citation citation2 = response.getCitations().get(1);
        assertThat(citation2.getIndex()).isEqualTo(2);
        assertThat(citation2.getSegmentId()).isEqualTo("seg-002");
        assertThat(citation2.getDocName()).isEqualTo("standard.md");
        assertThat(citation2.getChunkContent()).contains("传输层控制器");
        assertThat(citation2.getSimilarityScore()).isEqualTo(0.88);
    }

    @Test
    @DisplayName("越界清洗防卫：自动清洗剔除越界非法引用序号并降低忠实度评分")
    void testOutOfBoundsCitationSanitization() {
        TextSegment seg1 = TextSegment.from("数据安全遵循内存真明文不变量",
                Metadata.from(Map.of("file_name", "security.md")));
        List<TextSegment> candidates = Collections.singletonList(seg1);

        // 候选仅有 1 个，[99] 和 [5] 均为越界幻觉序号；[1, 99] 混合序号应当清洗为 [1]
        String rawText = "真实事实[1]，虚构事实[99]，混合事实[1, 99]，另一虚构[5]。";

        RagResponse<String> response = CitationExtractor.extract(rawText, candidates);

        assertThat(response).isNotNull();
        // [99] 与 [5] 应该被完全剔除，[1, 99] 应该被清洗为 [1]
        assertThat(response.getResult()).isEqualTo("真实事实[1]，虚构事实，混合事实[1]，另一虚构。");
        // 有效 1 个，越界 2 个 (99, 5)，忠实度 1 / (1 + 2) = 0.33
        assertThat(response.getGroundingScore()).isEqualTo(0.33);
        assertThat(response.getCitations()).hasSize(1);
        assertThat(response.getCitations().get(0).getIndex()).isEqualTo(1);
        assertThat(response.getCitations().get(0).getDocName()).isEqualTo("security.md");
    }

    @Test
    @DisplayName("对抗性测试：空文本、空候选、无关中括号与无引用场景")
    void testEdgeCases() {
        // 1. 空文本
        RagResponse<String> emptyResponse = CitationExtractor.extract("", Collections.emptyList());
        assertThat(emptyResponse.getResult()).isEmpty();
        assertThat(emptyResponse.getCitations()).isEmpty();
        assertThat(emptyResponse.getGroundingScore()).isEqualTo(0.0);

        // 2. 有候选但无引用标记
        TextSegment seg = TextSegment.from("内容");
        RagResponse<String> noCitationResponse = CitationExtractor.extract("普通回答没有任何引用", Collections.singletonList(seg));
        assertThat(noCitationResponse.getCitations()).isEmpty();
        assertThat(noCitationResponse.getGroundingScore()).isEqualTo(0.0);

        // 3. 候选为空但模型输出了引用 [1]
        RagResponse<String> noCandidateResponse = CitationExtractor.extract("捏造引用[1]", Collections.emptyList());
        assertThat(noCandidateResponse.getResult()).isEqualTo("捏造引用");
        assertThat(noCandidateResponse.getCitations()).isEmpty();

        // 4. 无关的中括号文本不应被误判或破坏
        String irrelevantText = "Java 中的数组语法是 int[] arr，日志输出格式为 [INFO] 消息内容。";
        String sanitized = CitationExtractor.sanitizeText(irrelevantText, 3);
        assertThat(sanitized).isEqualTo(irrelevantText);
    }

    @Test
    @DisplayName("提示词构建与 Content 转换测试")
    void testBuildPromptAndFromContents() {
        TextSegment seg1 = TextSegment.from("Spring Boot 3 依赖基线", Metadata.from(Map.of("source", "pom.xml")));
        String prompt = CitationExtractor.buildPrompt("Spring Boot 版本是多少？", Collections.singletonList(seg1));

        assertThat(prompt).contains("Spring Boot 3 依赖基线");
        assertThat(prompt).contains("[1] (来源: pom.xml)");
        assertThat(prompt).contains("【用户问题】: Spring Boot 版本是多少？");

        // Content 转换测试
        Content content = Content.from(seg1);
        List<TextSegment> converted = CitationExtractor.fromContents(Collections.singletonList(content));
        assertThat(converted).hasSize(1);
        assertThat(converted.get(0).text()).isEqualTo("Spring Boot 3 依赖基线");
    }
}
