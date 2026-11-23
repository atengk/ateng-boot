package io.github.atengk.boot.ai.rag.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 父子分级切片器 (ParentChildSplitter) 与父上下文重组器 (ParentContextAggregator) 单元测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
@DisplayName("父子分级切片器 (ParentChildSplitter) 单元测试")
class ParentChildSplitterTest {

    @Test
    @DisplayName("父子分级切片：大块生成父分块，每个父分块细切子分块，子分块携带 parent_id 与 parent_text")
    void testParentChildBasicHierarchy() {
        // 父分块 200 字符，子分块 50 字符
        ParentChildSplitter splitter = new ParentChildSplitter(200, 20, 50, 10);

        String p1 = "第一大章节：介绍企业级应用整体架构与核心技术基线。基于 Spring Boot 3 与 JDK 17，深度结合云原生运维能力，保证单体与微服务的平滑演进。";
        String p2 = "第二大章节：介绍 RAG 企业知识库与父子分块切片。通过高精度子块召回提升语义匹配度，并借助自包含父上下文还原完整大模型思考上下文。";
        String fullText = p1 + "\n\n" + p2;

        Metadata metadata = Metadata.from(Map.of("doc_id", "DOC-PARENT-001", "title", "架构指引"));
        List<TextSegment> childSegments = splitter.split(fullText, metadata);

        assertThat(childSegments).isNotEmpty();
        // 验证每一个子分块的元数据完整性
        for (int i = 0; i < childSegments.size(); i++) {
            TextSegment child = childSegments.get(i);
            assertThat(child.metadata().getString("doc_id")).isEqualTo("DOC-PARENT-001");
            assertThat(child.metadata().getString("title")).isEqualTo("架构指引");
            assertThat(child.metadata().getString("parent_id")).isNotBlank();
            assertThat(child.metadata().getInteger("parent_index")).isNotNull();
            assertThat(child.metadata().getInteger("child_index")).isNotNull();
            assertThat(child.metadata().getInteger("total_children_in_parent")).isGreaterThanOrEqualTo(1);
            assertThat(child.metadata().getInteger("global_child_index")).isEqualTo(i);
            assertThat(child.metadata().getInteger("total_global_children")).isEqualTo(childSegments.size());

            // 自包含父分块内容
            String parentText = child.metadata().getString("parent_text");
            assertThat(parentText).isNotBlank();
            assertThat(parentText).contains(child.text());
        }
    }

    @Test
    @DisplayName("splitHierarchy: 返回包含完整父分块列表、子分块列表及父子映射关系的聚合结果")
    void testSplitHierarchyMethod() {
        ParentChildSplitter splitter = new ParentChildSplitter(150, 20, 50, 10);
        String text = "模块 A 负责用户认证鉴权与单点登录。\n\n模块 B 负责数据持久化与分布式缓存。\n\n模块 C 负责消息异步解耦与削峰填谷。";

        ParentChildResult result = splitter.splitHierarchy(text, Metadata.from(Map.of("doc_id", "DOC-002")));

        assertThat(result.parents()).isNotEmpty();
        assertThat(result.children()).isNotEmpty();
        assertThat(result.parentIdToChildren()).isNotEmpty();

        // 验证父子关联关系一致性
        for (TextSegment parent : result.parents()) {
            String parentId = parent.metadata().getString("parent_id");
            assertThat(parentId).isNotBlank();
            List<TextSegment> childrenOfParent = result.parentIdToChildren().get(parentId);
            assertThat(childrenOfParent).isNotEmpty();
            for (TextSegment child : childrenOfParent) {
                assertThat(child.metadata().getString("parent_id")).isEqualTo(parentId);
            }
        }
    }

    @Test
    @DisplayName("ParentContextAggregator: 多个命中相同 parent_id 的子分块自动去重并按原文顺序重构完整父上下文")
    void testParentContextAggregatorDeduplication() {
        ParentChildSplitter splitter = new ParentChildSplitter(120, 10, 40, 5);

        String text = "第一段父块内容，详细记录数据库高可用拓扑结构与读写分离配置规则，确保生产环境在节点宕机时能够秒级平滑转移，保障数据一致性与业务连续性。\n\n" +
                "第二段父块内容，详细记录分布式消息队列重试死信机制与顺序消费保障，结合本地消息表与事务反查实现最终一致性，杜绝消息丢失或重复消费。";
        List<TextSegment> allChildren = splitter.split(text);

        // 模拟召回：假设召回了来自同一父块的多个子块，以及来自第二父块的一个子块
        // 比如第 0、1、2 个子块来自同一个父块，第 3 个子块来自第二父块
        assertThat(allChildren.size()).isGreaterThanOrEqualTo(3);

        TextSegment c0 = allChildren.get(0);
        TextSegment c1 = allChildren.get(1);
        TextSegment lastChild = allChildren.get(allChildren.size() - 1);

        // 乱序混入
        List<TextSegment> retrievedHits = List.of(c1, lastChild, c0);

        // 1. 还原纯文本列表
        List<String> reconstructedTexts = ParentContextAggregator.reconstructParentTexts(retrievedHits);
        assertThat(reconstructedTexts).hasSize(2);
        // 按父块原始出现顺序排序
        assertThat(reconstructedTexts.get(0)).contains("第一段父块内容");
        assertThat(reconstructedTexts.get(1)).contains("第二段父块内容");

        // 2. 还原 TextSegment 列表并检查聚合指标
        List<TextSegment> reconstructedSegments = ParentContextAggregator.reconstructParentSegments(retrievedHits);
        assertThat(reconstructedSegments).hasSize(2);

        TextSegment parentSeg0 = reconstructedSegments.get(0);
        assertThat(parentSeg0.metadata().getInteger("matched_child_count")).isEqualTo(2); // c0 和 c1 命中该父块
        assertThat(parentSeg0.metadata().getString("parent_id")).isEqualTo(c0.metadata().getString("parent_id"));

        TextSegment parentSeg1 = reconstructedSegments.get(1);
        assertThat(parentSeg1.metadata().getInteger("matched_child_count")).isEqualTo(1);

        // 3. 聚合为 LLM 提示词上下文
        String promptContext = ParentContextAggregator.aggregateToPromptContext(retrievedHits, "\n\n---\n\n");
        assertThat(promptContext)
                .contains("第一段父块内容")
                .contains("第二段父块内容")
                .contains("\n\n---\n\n");
    }

    @Test
    @DisplayName("ParentContextAggregator: 兼顾未挂载 parent_id 的普通切片退化保底")
    void testParentContextAggregatorFallback() {
        TextSegment normalSeg = TextSegment.from("这是一段普通没有父级元数据的切片内容。");
        List<String> texts = ParentContextAggregator.reconstructParentTexts(List.of(normalSeg));
        assertThat(texts).containsExactly("这是一段普通没有父级元数据的切片内容。");

        List<TextSegment> segments = ParentContextAggregator.reconstructParentSegments(List.of(normalSeg));
        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).text()).isEqualTo("这是一段普通没有父级元数据的切片内容。");
    }

    @Test
    @DisplayName("空值与防御性参数校验")
    void testParentChildSplitterDefensive() {
        ParentChildSplitter splitter = new ParentChildSplitter();
        assertThat(splitter.split((String) null)).isEmpty();
        assertThat(splitter.split("")).isEmpty();
        assertThat(splitter.split("   ")).isEmpty();

        assertThatThrownBy(() -> new ParentChildSplitter(100, 20, 200, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("childChunkSize 必须小于 parentChunkSize");

        assertThatThrownBy(() -> new ParentChildSplitter(0, 0, 10, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parentChunkSize 必须大于 0");
    }

    @Test
    @DisplayName("Document 与 Spring Resource 输入重载正常切片")
    void testDocumentAndResourceOverload() {
        ParentChildSplitter splitter = new ParentChildSplitter(100, 10, 30, 5);

        Document doc = Document.from("这是通过 Document 载入的父子切片长文本测试内容。");
        List<TextSegment> docSegments = splitter.split(doc);
        assertThat(docSegments).isNotEmpty();
        assertThat(docSegments.get(0).metadata().getString("parent_id")).isNotBlank();

        ByteArrayResource resource = new ByteArrayResource("这是通过 Spring Resource 载入的父子切片测试内容。".getBytes(StandardCharsets.UTF_8));
        List<TextSegment> resSegments = splitter.split(resource);
        assertThat(resSegments).isNotEmpty();
    }
}
