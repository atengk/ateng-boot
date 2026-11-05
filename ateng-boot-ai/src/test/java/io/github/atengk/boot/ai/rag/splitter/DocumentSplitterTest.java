package io.github.atengk.boot.ai.rag.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.github.atengk.boot.ai.template.EmbeddingTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 原生长文本智能切片门面全场景单测
 *
 * @author Ateng
 * @since 2026-11-05
 */
@DisplayName("原生长文本智能切片门面 (DocumentSplitter) 全场景单测")
class DocumentSplitterTest {

    @Test
    @DisplayName("RecursiveCharacterSplitter: 短文本小于分块大小时直接返回单个切片并挂载完备元数据")
    void testRecursiveSplitterShortText() {
        RecursiveCharacterSplitter splitter = new RecursiveCharacterSplitter(100, 20);
        String text = "这是一个较短的句子，长度不足100字符。";

        Metadata initialMeta = Metadata.from(Map.of("author", "Ateng", "doc_id", "DOC-001"));
        List<TextSegment> segments = splitter.split(text, initialMeta);

        assertThat(segments).hasSize(1);
        TextSegment seg = segments.get(0);
        assertThat(seg.text()).isEqualTo(text);
        assertThat(seg.metadata().getString("author")).isEqualTo("Ateng");
        assertThat(seg.metadata().getString("doc_id")).isEqualTo("DOC-001");
        assertThat(seg.metadata().getInteger("chunk_index")).isEqualTo(0);
        assertThat(seg.metadata().getInteger("total_chunks")).isEqualTo(1);
        assertThat(seg.metadata().getInteger("char_count")).isEqualTo(text.length());
    }

    @Test
    @DisplayName("RecursiveCharacterSplitter: 长文本依据段落换行与标点分块并保持重叠连续性")
    void testRecursiveSplitterLongTextWithOverlap() {
        RecursiveCharacterSplitter splitter = new RecursiveCharacterSplitter(50, 15);

        String p1 = "第一段非常重要，阐述了系统核心架构与设计不变量。";
        String p2 = "第二段介绍微服务注册中心与网关路由规则，保障高可用。";
        String p3 = "第三段展示了高并发限流熔断与故障自动转移策略。";
        String fullText = p1 + "\n\n" + p2 + "\n\n" + p3;

        List<TextSegment> segments = splitter.split(fullText);

        assertThat(segments).isNotEmpty();
        assertThat(segments.size()).isGreaterThanOrEqualTo(3);

        // 验证元数据连续性
        for (int i = 0; i < segments.size(); i++) {
            TextSegment seg = segments.get(i);
            assertThat(seg.metadata().getInteger("chunk_index")).isEqualTo(i);
            assertThat(seg.metadata().getInteger("total_chunks")).isEqualTo(segments.size());
            assertThat(seg.text().length()).isLessThanOrEqualTo(80); // 宽松包含重叠与整句
        }

        // 验证内容覆盖完整性：所有段落核心关键字均应被包含
        String joined = segments.stream().map(TextSegment::text).reduce("", (a, b) -> a + " " + b);
        assertThat(joined).contains("第一段非常重要").contains("第二段介绍微服务").contains("第三段展示了");
    }

    @Test
    @DisplayName("RecursiveCharacterSplitter: 空白或空输入防御性返回空列表")
    void testRecursiveSplitterEmptyInput() {
        RecursiveCharacterSplitter splitter = new RecursiveCharacterSplitter();

        assertThat(splitter.split((String) null)).isEmpty();
        assertThat(splitter.split("")).isEmpty();
        assertThat(splitter.split("   \n\t  ")).isEmpty();
    }

    @Test
    @DisplayName("RecursiveCharacterSplitter: 构造参数非法卫语句校验")
    void testRecursiveSplitterDefensiveAssertions() {
        assertThatThrownBy(() -> new RecursiveCharacterSplitter(0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunkSize 必须大于 0");

        assertThatThrownBy(() -> new RecursiveCharacterSplitter(100, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunkOverlap 不能为负数");

        assertThatThrownBy(() -> new RecursiveCharacterSplitter(100, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunkOverlap 必须小于 chunkSize");

        assertThatThrownBy(() -> new RecursiveCharacterSplitter(100, 150))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunkOverlap 必须小于 chunkSize");

        assertThatThrownBy(() -> new RecursiveCharacterSplitter(100, 20, List.of(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("separators 不能为空");
    }

    @Test
    @DisplayName("MarkdownHeaderSplitter: 按照各级标题层级分块并继承祖先标题元数据")
    void testMarkdownHeaderSplitterHierarchy() {
        MarkdownHeaderSplitter splitter = new MarkdownHeaderSplitter();

        String markdown = """
                # 项目概述
                本项目是 Spring Boot 3 工业级基础设施 Starter 套件。
                
                ## 架构原则
                坚持纯原生、零臃肿依赖与强类型契约。
                
                ### 数据安全
                内存真明文不变量，入库加密出库解密。
                
                ## 高可用设计
                支持多模型动态切源与故障自动转移。
                """;

        List<TextSegment> segments = splitter.split(markdown);

        assertThat(segments).hasSize(4);

        // 章节 1: 项目概述
        TextSegment s1 = segments.get(0);
        assertThat(s1.metadata().getString("h1")).isEqualTo("项目概述");
        assertThat(s1.metadata().containsKey("h2")).isFalse();
        assertThat(s1.text()).contains("本项目是 Spring Boot 3 工业级基础设施");

        // 章节 2: 架构原则
        TextSegment s2 = segments.get(1);
        assertThat(s2.metadata().getString("h1")).isEqualTo("项目概述");
        assertThat(s2.metadata().getString("h2")).isEqualTo("架构原则");
        assertThat(s2.text()).contains("坚持纯原生、零臃肿依赖与强类型契约");

        // 章节 3: 数据安全 (h3)
        TextSegment s3 = segments.get(2);
        assertThat(s3.metadata().getString("h1")).isEqualTo("项目概述");
        assertThat(s3.metadata().getString("h2")).isEqualTo("架构原则");
        assertThat(s3.metadata().getString("h3")).isEqualTo("数据安全");
        assertThat(s3.text()).contains("内存真明文不变量");

        // 章节 4: 高可用设计 (h2，应清除旧 h3)
        TextSegment s4 = segments.get(3);
        assertThat(s4.metadata().getString("h1")).isEqualTo("项目概述");
        assertThat(s4.metadata().getString("h2")).isEqualTo("高可用设计");
        assertThat(s4.metadata().containsKey("h3")).isFalse();
        assertThat(s4.text()).contains("支持多模型动态切源与故障自动转移");
    }

    @Test
    @DisplayName("MarkdownHeaderSplitter: 代码块围栏内部的 # 字符不应误判为标题")
    void testMarkdownHeaderSplitterCodeBlockProtection() {
        MarkdownHeaderSplitter splitter = new MarkdownHeaderSplitter();

        String markdown = """
                # 代码指南
                以下是 Python 代码示例：
                ```python
                # 这是一个 Python 注释，绝不是 Markdown 标题
                def hello():
                    print("Hello World")
                ```
                代码展示完毕。
                """;

        List<TextSegment> segments = splitter.split(markdown);

        // 应该只有 1 个章节，不被代码块内部的 # 切断
        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).metadata().getString("h1")).isEqualTo("代码指南");
        assertThat(segments.get(0).text()).contains("# 这是一个 Python 注释");
    }

    @Test
    @DisplayName("MarkdownHeaderSplitter: 单章节超长时自动级联二次切片")
    void testMarkdownHeaderSplitterCascadeMaxChunkSize() {
        // maxChunkSize=50, chunkOverlap=10
        MarkdownHeaderSplitter splitter = new MarkdownHeaderSplitter(50, 10);

        String markdown = """
                # 超长章节
                这段内容非常长，远远超过了五十个字符的限制，系统应该自动调用底层递归字符分块器对本章节进行二次切片，同时在所有子切片中保留父级标题元数据。
                """;

        List<TextSegment> segments = splitter.split(markdown);

        assertThat(segments.size()).isGreaterThanOrEqualTo(2);
        for (TextSegment seg : segments) {
            assertThat(seg.metadata().getString("h1")).isEqualTo("超长章节");
            assertThat(seg.metadata().containsKey("chunk_index")).isTrue();
            assertThat(seg.metadata().containsKey("total_chunks")).isTrue();
        }
    }

    @Test
    @DisplayName("DocumentSplitter: 支持 Spring Resource 资源与 InputStream 输入流切片")
    void testSplitResourceAndInputStream() {
        DocumentSplitter splitter = new RecursiveCharacterSplitter(60, 10);

        String content = "Spring Resource 测试内容：通过内存 ByteArrayResource 验证字符流切片能力。";
        Resource resource = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8), "guide.txt");

        List<TextSegment> resourceSegments = splitter.split(resource);
        assertThat(resourceSegments).isNotEmpty();
        assertThat(resourceSegments.get(0).metadata().getString("file_name")).isEqualTo("guide.txt");

        // InputStream 测试
        ByteArrayInputStream in = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        List<TextSegment> streamSegments = splitter.split(in, StandardCharsets.UTF_8);
        assertThat(streamSegments).isNotEmpty();
        assertThat(streamSegments.get(0).text()).contains("Spring Resource 测试内容");
    }

    @Test
    @DisplayName("DocumentSplitter: LangChain4j Document 标准接口适配")
    void testLangChain4jDocumentAdapter() {
        DocumentSplitter splitter = new RecursiveCharacterSplitter(50, 10);

        Document doc = Document.from("这是来自 LangChain4j 标准 Document 的内容。", Metadata.from("source", "wiki"));
        List<TextSegment> segments = splitter.split(doc);

        assertThat(segments).isNotEmpty();
        assertThat(segments.get(0).metadata().getString("source")).isEqualTo("wiki");
    }

    @Test
    @DisplayName("DocumentSplitter 与 EmbeddingTemplate & InMemoryEmbeddingStore 端到端入库闭环集成")
    void testInteroperabilityWithEmbeddingTemplate() {
        EmbeddingModel mockModel = mock(EmbeddingModel.class);
        EmbeddingTemplate template = new EmbeddingTemplate(mockModel);
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();

        DocumentSplitter splitter = new RecursiveCharacterSplitter(20, 5);
        String article = "智能知识库端到端切片入库测试。第一部分讲述向量化。第二部分讲述检索。";

        List<TextSegment> segments = splitter.split(article);
        assertThat(segments).hasSizeGreaterThanOrEqualTo(2);

        // Mock 向量化返回
        float[] dummyVector = new float[]{0.1f, 0.2f, 0.3f};
        List<Embedding> embeddings = segments.stream().map(s -> new Embedding(dummyVector)).toList();
        when(mockModel.embedAll(anyList())).thenReturn(Response.from(embeddings));

        // 批量入库
        List<String> ids = template.ingestAll(store, segments);

        assertThat(ids).hasSameSizeAs(segments);
        // 对称清空
        template.removeAll(store, ids);
    }
}
