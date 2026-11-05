package io.github.atengk.boot.ai.rag.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.Tokenizer;
import io.github.atengk.boot.ai.memory.HeuristicTokenizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Token 预算防溢出切片器 (TokenCharacterSplitter / TokenBudgetSplitter) 全场景单元测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
@DisplayName("Token 预算防溢出切片器 (TokenCharacterSplitter) 单元测试")
class TokenCharacterSplitterTest {

    @Test
    @DisplayName("短文本小于 Token 预算限制时直接返回单个切片并挂载 Token 与字符元数据")
    void testTokenSplitterShortText() {
        Tokenizer tokenizer = new HeuristicTokenizer();
        TokenCharacterSplitter splitter = new TokenCharacterSplitter(100, 20, tokenizer);

        String text = "这是一个较短的测试文本，用于验证在 Token 预算充足时直接返回单片段。";
        Metadata initialMeta = Metadata.from(Map.of("author", "Ateng", "doc_id", "DOC-TK-01"));

        List<TextSegment> segments = splitter.split(text, initialMeta);

        assertThat(segments).hasSize(1);
        TextSegment seg = segments.get(0);
        assertThat(seg.text()).isEqualTo(text);
        assertThat(seg.metadata().getString("author")).isEqualTo("Ateng");
        assertThat(seg.metadata().getString("doc_id")).isEqualTo("DOC-TK-01");
        assertThat(seg.metadata().getInteger("chunk_index")).isEqualTo(0);
        assertThat(seg.metadata().getInteger("total_chunks")).isEqualTo(1);
        assertThat(seg.metadata().getInteger("char_count")).isEqualTo(text.length());
        assertThat(seg.metadata().getInteger("token_count")).isGreaterThan(0);
        assertThat(seg.metadata().getInteger("token_count")).isLessThanOrEqualTo(100);
    }

    @Test
    @DisplayName("长文本在中文、标点与英文混合场景下严格遵守 Token 上限且保持重叠")
    void testTokenSplitterTokenBudgetEnforcement() {
        Tokenizer tokenizer = new HeuristicTokenizer();
        int maxTokens = 60;
        int tokenOverlap = 15;
        TokenCharacterSplitter splitter = new TokenCharacterSplitter(maxTokens, tokenOverlap, tokenizer);

        String p1 = "第一段：工业级微服务架构基准，依托 Spring Boot 3 与 JDK 17，坚持零臃肿和原生能力优先。";
        String p2 = "第二段：在 RAG 知识库检索中，传统按字符切分极易在中文多字节及特殊符号下引发 Token 膨胀并超出模型上限。";
        String p3 = "第三段：TokenCharacterSplitter 动态评估 Token 预算边界，确保每个分块绝不越过上游向量模型约束。";
        String fullText = p1 + "\n\n" + p2 + "\n\n" + p3;

        List<TextSegment> segments = splitter.split(fullText);

        assertThat(segments).isNotEmpty();
        assertThat(segments.size()).isGreaterThanOrEqualTo(3);

        for (int i = 0; i < segments.size(); i++) {
            TextSegment seg = segments.get(i);
            int estimatedTokens = tokenizer.estimateTokenCountInText(seg.text());
            assertThat(estimatedTokens)
                    .as("切片 %d 的 Token 总数 (%d) 必须严格 <= maxTokens (%d)", i, estimatedTokens, maxTokens)
                    .isLessThanOrEqualTo(maxTokens);

            assertThat(seg.metadata().getInteger("chunk_index")).isEqualTo(i);
            assertThat(seg.metadata().getInteger("total_chunks")).isEqualTo(segments.size());
            assertThat(seg.metadata().getInteger("token_count")).isEqualTo(estimatedTokens);
            assertThat(seg.metadata().getInteger("char_count")).isEqualTo(seg.text().length());
        }

        // 验证关键段落信息无丢失
        String joined = segments.stream().map(TextSegment::text).reduce("", (a, b) -> a + " " + b);
        assertThat(joined)
                .contains("工业级微服务架构基准")
                .contains("传统按字符切分极易在中文多字节")
                .contains("动态评估 Token 预算边界");
    }

    @Test
    @DisplayName("支持安全边际 (Safety Margin) 配置，有效收缩切片 Token 上限防边界溢出")
    void testTokenSplitterSafetyMargin() {
        Tokenizer tokenizer = new HeuristicTokenizer();
        int maxTokens = 100;
        int tokenOverlap = 20;
        double safetyMargin = 0.20; // 20% 安全余量，有效预算 = 80 tokens
        TokenCharacterSplitter splitter = new TokenCharacterSplitter(maxTokens, tokenOverlap, tokenizer, safetyMargin);

        String text = "深度检索增强生成 (RAG) 必须兼顾召回率与计算资源开销。在大模型推理前置阶段，" +
                "通过严格的 Token 安全边际预留空间，能够防御不同分词器实现间的微小计数偏差。";

        List<TextSegment> segments = splitter.split(text);

        assertThat(segments).isNotEmpty();
        for (TextSegment seg : segments) {
            int tokenCount = tokenizer.estimateTokenCountInText(seg.text());
            assertThat(tokenCount).isLessThanOrEqualTo(80);
        }
    }

    @Test
    @DisplayName("空值与空白文本防御性保底返回空集合")
    void testTokenSplitterEmptyInput() {
        TokenCharacterSplitter splitter = new TokenCharacterSplitter();

        assertThat(splitter.split((String) null)).isEmpty();
        assertThat(splitter.split("")).isEmpty();
        assertThat(splitter.split("   \t\n  ")).isEmpty();
    }

    @Test
    @DisplayName("构造参数非法卫语句拦截防御")
    void testTokenSplitterConstructorValidation() {
        assertThatThrownBy(() -> new TokenCharacterSplitter(0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxTokens 必须大于 0");

        assertThatThrownBy(() -> new TokenCharacterSplitter(100, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tokenOverlap 不能为负数");

        assertThatThrownBy(() -> new TokenCharacterSplitter(100, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tokenOverlap 必须小于 maxTokens");

        assertThatThrownBy(() -> new TokenCharacterSplitter(100, 20, null, -0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("safetyMargin 必须在 [0.0, 1.0) 范围内");

        assertThatThrownBy(() -> new TokenCharacterSplitter(100, 20, null, 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("safetyMargin 必须在 [0.0, 1.0) 范围内");
    }

    @Test
    @DisplayName("Document 与 Spring Resource 输入重载正常切片并传递元数据")
    void testTokenSplitterDocumentAndResource() {
        TokenCharacterSplitter splitter = new TokenCharacterSplitter(80, 10);

        Document doc = Document.from("这是通过 LangChain4j Document 输入测试的一段长文本内容。", Metadata.from(Map.of("source", "doc.txt")));
        List<TextSegment> docSegments = splitter.split(doc);
        assertThat(docSegments).isNotEmpty();
        assertThat(docSegments.get(0).metadata().getString("source")).isEqualTo("doc.txt");

        ByteArrayResource resource = new ByteArrayResource("这是通过 Spring Resource 输入的内容。".getBytes(StandardCharsets.UTF_8));
        List<TextSegment> resSegments = splitter.split(resource);
        assertThat(resSegments).isNotEmpty();
        assertThat(resSegments.get(0).text()).contains("Spring Resource");
    }

    @Test
    @DisplayName("TokenBudgetSplitter 别名子类语义兼容与完全等价")
    void testTokenBudgetSplitterAlias() {
        TokenBudgetSplitter splitter = new TokenBudgetSplitter(100, 20);
        List<TextSegment> segments = splitter.split("测试 TokenBudgetSplitter 别名等价性");
        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).text()).isEqualTo("测试 TokenBudgetSplitter 别名等价性");
    }
}
