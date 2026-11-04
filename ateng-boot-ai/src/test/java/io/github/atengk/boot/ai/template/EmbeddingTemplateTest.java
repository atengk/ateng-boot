package io.github.atengk.boot.ai.template;

import dev.ai4j.openai4j.OpenAiHttpException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.properties.AiModelConfig;
import io.github.atengk.boot.ai.properties.AtengAiProperties;
import io.github.atengk.boot.ai.properties.AtengAiRagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * EmbeddingTemplate 统一向量化嵌入门面全维度单测
 *
 * @author Ateng
 * @since 2026-11-04
 */
@DisplayName("EmbeddingTemplate 统一向量化嵌入门面全维度单测")
class EmbeddingTemplateTest {

    private EmbeddingModel mockEmbeddingModel;
    private EmbeddingTemplate template;

    @BeforeEach
    void setUp() {
        mockEmbeddingModel = mock(EmbeddingModel.class);
        AtengAiProperties properties = new AtengAiProperties();
        // 设置极短的重试延迟，确保测试极速通过
        properties.getModel().setMaxRetries(2);
        properties.getModel().setRetryInitialInterval(Duration.ofMillis(10));
        properties.getModel().setRetryMaxInterval(Duration.ofMillis(50));
        template = new EmbeddingTemplate(mockEmbeddingModel, properties);
    }

    @Test
    @DisplayName("单文本向量化成功且结果正确")
    void testEmbedSingleTextSuccess() {
        float[] vector = new float[]{0.1f, 0.2f, 0.3f};
        Embedding embedding = new Embedding(vector);
        when(mockEmbeddingModel.embed("Hello LangChain4j")).thenReturn(Response.from(embedding));

        Response<Embedding> response = template.embed("Hello LangChain4j");

        assertThat(response).isNotNull();
        assertThat(response.content()).isEqualTo(embedding);
        assertThat(response.content().vector()).containsExactly(0.1f, 0.2f, 0.3f);
        verify(mockEmbeddingModel, times(1)).embed("Hello LangChain4j");
    }

    @Test
    @DisplayName("单文本为空或空白字符时防御性抛出 IllegalArgumentException")
    void testEmbedSingleTextBlankException() {
        assertThatThrownBy(() -> template.embed((String) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("待向量化文本不能为空");

        assertThatThrownBy(() -> template.embed("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("待向量化文本不能为空");

        verifyNoInteractions(mockEmbeddingModel);
    }

    @Test
    @DisplayName("批量文本向量化成功且结果正确")
    void testEmbedMultipleTextsSuccess() {
        float[] v1 = new float[]{0.1f, 0.2f};
        float[] v2 = new float[]{0.3f, 0.4f};
        List<Embedding> embeddings = List.of(new Embedding(v1), new Embedding(v2));
        when(mockEmbeddingModel.embedAll(anyList())).thenReturn(Response.from(embeddings));

        Response<List<Embedding>> response = template.embed(List.of("文本1", "文本2"));

        assertThat(response).isNotNull();
        assertThat(response.content()).hasSize(2);
        verify(mockEmbeddingModel, times(1)).embedAll(anyList());
    }

    @Test
    @DisplayName("批量文本列表为空时保底返回空响应")
    void testEmbedMultipleTextsEmptyReturnsEmpty() {
        Response<List<Embedding>> r1 = template.embed((List<String>) null);
        assertThat(r1).isNotNull();
        assertThat(r1.content()).isEmpty();

        Response<List<Embedding>> r2 = template.embed(Collections.emptyList());
        assertThat(r2).isNotNull();
        assertThat(r2.content()).isEmpty();

        verifyNoInteractions(mockEmbeddingModel);
    }

    @Test
    @DisplayName("单 TextSegment 向量化成功")
    void testEmbedSegmentSuccess() {
        TextSegment segment = TextSegment.from("知识库切片001");
        Embedding embedding = new Embedding(new float[]{0.5f, 0.6f});
        when(mockEmbeddingModel.embed(segment)).thenReturn(Response.from(embedding));

        Response<Embedding> response = template.embed(segment);

        assertThat(response).isNotNull();
        assertThat(response.content().vector()).containsExactly(0.5f, 0.6f);
        verify(mockEmbeddingModel, times(1)).embed(segment);
    }

    @Test
    @DisplayName("TextSegment 为空时防御性抛出 IllegalArgumentException")
    void testEmbedSegmentNullException() {
        assertThatThrownBy(() -> template.embed((TextSegment) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TextSegment 文本片段对象不能为空");
        verifyNoInteractions(mockEmbeddingModel);
    }

    @Test
    @DisplayName("批量 TextSegment 列表为空时保底返回空响应")
    void testEmbedMultipleSegmentsEmptyReturnsEmpty() {
        Response<List<Embedding>> r1 = template.embedSegments(null);
        assertThat(r1).isNotNull();
        assertThat(r1.content()).isEmpty();

        Response<List<Embedding>> r2 = template.embedSegments(Collections.emptyList());
        assertThat(r2).isNotNull();
        assertThat(r2.content()).isEmpty();

        verifyNoInteractions(mockEmbeddingModel);
    }

    @Test
    @DisplayName("embedVector 与 embedVectors 便捷数组提取")
    void testEmbedVectorShortcuts() {
        float[] v1 = new float[]{1.0f, 2.0f};
        float[] v2 = new float[]{3.0f, 4.0f};
        when(mockEmbeddingModel.embed("single")).thenReturn(Response.from(new Embedding(v1)));
        when(mockEmbeddingModel.embedAll(anyList())).thenReturn(Response.from(List.of(new Embedding(v1), new Embedding(v2))));

        float[] vector = template.embedVector("single");
        assertThat(vector).containsExactly(1.0f, 2.0f);

        List<float[]> vectors = template.embedVectors(List.of("t1", "t2"));
        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(1.0f, 2.0f);
        assertThat(vectors.get(1)).containsExactly(3.0f, 4.0f);
    }

    @Test
    @DisplayName("获取向量维度与底层模型实例")
    void testDimensionAndGetModel() {
        when(mockEmbeddingModel.dimension()).thenReturn(1536);
        assertThat(template.dimension()).isEqualTo(1536);
        assertThat(template.getModel()).isSameAs(mockEmbeddingModel);
    }

    @Test
    @DisplayName("瞬时 429 限流异常触发指数退避重试并成功恢复")
    void testRetryOnTransient429Success() {
        AtomicInteger attempts = new AtomicInteger(0);
        float[] v = new float[]{0.7f, 0.8f};
        when(mockEmbeddingModel.embed("retryText")).thenAnswer(inv -> {
            int attempt = attempts.incrementAndGet();
            if (attempt <= 2) {
                throw new OpenAiHttpException(429, "Rate limit exceeded (Too Many Requests)");
            }
            return Response.from(new Embedding(v));
        });

        Response<Embedding> response = template.embed("retryText");

        assertThat(response).isNotNull();
        assertThat(response.content().vector()).containsExactly(0.7f, 0.8f);
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("重试耗尽后转译为语义化 AiException 且完整保留因果链")
    void testRetryExhaustionThrowsAiException() {
        when(mockEmbeddingModel.embed("alwaysFail"))
                .thenThrow(new OpenAiHttpException(429, "Rate limit exceeded"));

        assertThatThrownBy(() -> template.embed("alwaysFail"))
                .isInstanceOf(AiException.class)
                .satisfies(ex -> {
                    AiException aiEx = (AiException) ex;
                    assertThat(aiEx.getResultCode()).isEqualTo(AiResultCode.AI_RATE_LIMIT_EXCEEDED);
                    assertThat(aiEx.getCause()).isNotNull();
                });
    }

    @Test
    @DisplayName("非重试 401 认证异常立即失败且无多余重试")
    void testNonRetryableAuthErrorImmediatelyFails() {
        AtomicInteger attempts = new AtomicInteger(0);
        when(mockEmbeddingModel.embed("authError")).thenAnswer(inv -> {
            attempts.incrementAndGet();
            throw new OpenAiHttpException(401, "Invalid API Key");
        });

        assertThatThrownBy(() -> template.embed("authError"))
                .isInstanceOf(AiException.class)
                .satisfies(ex -> {
                    AiException aiEx = (AiException) ex;
                    assertThat(aiEx.getResultCode()).isEqualTo(AiResultCode.AI_AUTH_FAILED);
                });

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("ingest 与 ingestAll 写入 InMemoryEmbeddingStore 成功")
    void testIngestSuccess() {
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        when(mockEmbeddingModel.embed(any(TextSegment.class)))
                .thenReturn(Response.from(new Embedding(new float[]{0.1f, 0.2f})));
        when(mockEmbeddingModel.embedAll(anyList()))
                .thenReturn(Response.from(List.of(
                        new Embedding(new float[]{0.3f, 0.4f}),
                        new Embedding(new float[]{0.5f, 0.6f})
                )));

        // 1. 单文本入库
        String id1 = template.ingest(store, "企业研发规范一：严禁泄露 Entity");
        assertThat(id1).isNotBlank();

        // 2. 单 TextSegment 入库
        TextSegment segment = TextSegment.from("企业研发规范二：统一 Result 显式契约");
        String id2 = template.ingest(store, segment);
        assertThat(id2).isNotBlank();

        // 3. 批量 TextSegment 入库
        List<String> ids = template.ingestAll(store, List.of(
                TextSegment.from("规范三"),
                TextSegment.from("规范四")
        ));
        assertThat(ids).hasSize(2);
    }

    @Test
    @DisplayName("ingest 前置参数防卫断言")
    void testIngestDefenseInDepth() {
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();

        assertThatThrownBy(() -> template.ingest(null, "text"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("目标 EmbeddingStore 存储库不能为空");

        assertThatThrownBy(() -> template.ingest(store, (String) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("待存储文本内容不能为空");

        assertThatThrownBy(() -> template.ingest(null, TextSegment.from("text")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("目标 EmbeddingStore 存储库不能为空");

        assertThatThrownBy(() -> template.ingest(store, (TextSegment) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("待存储 TextSegment 不能为空");

        assertThatThrownBy(() -> template.ingestAll(null, List.of(TextSegment.from("t"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("目标 EmbeddingStore 存储库不能为空");

        List<String> emptyIds = template.ingestAll(store, Collections.emptyList());
        assertThat(emptyIds).isEmpty();
    }

    @Test
    @DisplayName("验证 remove/removeAll 对称回收操作")
    void testRemoveSymmetricOperations() {
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();

        when(mockEmbeddingModel.embed(any(TextSegment.class)))
                .thenReturn(Response.from(Embedding.from(new float[]{0.1f, 0.2f, 0.3f})));

        String id1 = template.ingest(store, "规范一");
        String id2 = template.ingest(store, "规范二");
        String id3 = template.ingest(store, "规范三");

        // 1. 单条删除
        template.remove(store, id1);

        // 2. 批量删除
        template.removeAll(store, List.of(id2));

        // 3. 全部清空
        template.removeAll(store);

        // 4. 空安全防卫
        template.remove(store, null);
        template.removeAll(store, Collections.emptyList());

        assertThatThrownBy(() -> template.remove(null, "id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.removeAll(null, List.of("id")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.removeAll(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
