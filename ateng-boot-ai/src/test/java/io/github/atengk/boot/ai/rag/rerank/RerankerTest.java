package io.github.atengk.boot.ai.rag.rerank;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.github.atengk.boot.ai.exception.AiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Reranker 交叉重排模型与复合检索器全场景单测
 *
 * @author Ateng
 * @since 2026-11-05
 */
@DisplayName("Reranker 交叉重排模型与复合检索器 (RerankingContentRetriever) 全场景单测")
class RerankerTest {

    @Test
    @DisplayName("OpenAiCompatibleReranker: 成功调用标准 REST 接口并完成相关度精排")
    @SuppressWarnings("unchecked")
    void testOpenAiCompatibleRerankerSuccess() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);

        String mockResponseBody = """
                {
                  "results": [
                    {"index": 1, "relevance_score": 0.985},
                    {"index": 0, "relevance_score": 0.320}
                  ]
                }
                """;
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(mockResponseBody);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        OpenAiCompatibleReranker reranker = new OpenAiCompatibleReranker(
                "https://api.siliconflow.cn/v1",
                "test-api-key",
                "BAAI/bge-reranker-v2-m3",
                Duration.ofSeconds(3),
                null,
                mockHttpClient
        );

        TextSegment seg0 = TextSegment.from("这是关于水果苹果的介绍");
        TextSegment seg1 = TextSegment.from("这是关于苹果 iPhone 16 手机的参数说明");

        List<RerankResult> results = reranker.rerank("iPhone 16 价格", List.of(seg0, seg1), 2);

        assertThat(results).hasSize(2);
        // 得分高者排在首位
        assertThat(results.get(0).segment()).isEqualTo(seg1);
        assertThat(results.get(0).score()).isEqualTo(0.985);
        assertThat(results.get(0).index()).isEqualTo(1);

        assertThat(results.get(1).segment()).isEqualTo(seg0);
        assertThat(results.get(1).score()).isEqualTo(0.320);
        assertThat(results.get(1).index()).isEqualTo(0);
    }

    @Test
    @DisplayName("OpenAiCompatibleReranker: 防御性边界入参检查")
    void testOpenAiCompatibleRerankerDefensiveChecks() {
        OpenAiCompatibleReranker reranker = new OpenAiCompatibleReranker("https://api.rerank.com", "key");

        // 空入参保底返回空列表
        assertThat(reranker.rerank("", List.of(TextSegment.from("A")), 1)).isEmpty();
        assertThat(reranker.rerank("query", null, 1)).isEmpty();
        assertThat(reranker.rerank("query", List.of(), 1)).isEmpty();
        assertThat(reranker.rerank("query", List.of(TextSegment.from("A")), 0)).isEmpty();

        // 非法 URL 断言
        assertThatThrownBy(() -> new OpenAiCompatibleReranker("", "key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Rerank baseUrl 不能为空");
    }

    @Test
    @DisplayName("OpenAiCompatibleReranker: 兼容 HuggingFace TEI 根数组响应格式与单切片真实精排")
    @SuppressWarnings("unchecked")
    void testOpenAiCompatibleRerankerTeiFormat() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);

        // TEI 返回根数组格式: [{"index": 0, "score": 0.88}]
        String teiResponseBody = """
                [
                  {"index": 0, "score": 0.88}
                ]
                """;
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(teiResponseBody);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        OpenAiCompatibleReranker reranker = new OpenAiCompatibleReranker(
                "http://tei-server:8080/rerank",
                "test-api-key",
                "BAAI/bge-reranker-base",
                Duration.ofSeconds(3),
                0.5,
                mockHttpClient
        );

        TextSegment single = TextSegment.from("单个候选片段");
        List<RerankResult> results = reranker.rerank("测试问题", List.of(single), 1);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).segment()).isEqualTo(single);
        assertThat(results.get(0).score()).isEqualTo(0.88);
        assertThat(results.get(0).index()).isEqualTo(0);
    }

    @Test
    @DisplayName("OpenAiCompatibleReranker: 远端服务 500 错误抛出语义化 AiException")
    @SuppressWarnings("unchecked")
    void testOpenAiCompatibleRerankerRemoteError() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);

        when(mockResponse.statusCode()).thenReturn(500);
        when(mockResponse.body()).thenReturn("Internal Server Error");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        OpenAiCompatibleReranker reranker = new OpenAiCompatibleReranker(
                "https://api.siliconflow.cn/v1",
                "key",
                "model",
                Duration.ofSeconds(1),
                null,
                mockHttpClient
        );

        List<TextSegment> segments = List.of(TextSegment.from("A"), TextSegment.from("B"));
        assertThatThrownBy(() -> reranker.rerank("query", segments, 2))
                .isInstanceOf(AiException.class)
                .hasMessageContaining("调用外部 Rerank 服务异常");
    }

    @Test
    @DisplayName("RerankingContentRetriever: 两阶段复合检索初筛召回与重排精排全流程")
    void testRerankingContentRetrieverTwoStage() {
        ContentRetriever mockDelegate = mock(ContentRetriever.class);
        Reranker mockReranker = mock(Reranker.class);

        TextSegment seg1 = TextSegment.from("候选片段1 - 一般相关");
        TextSegment seg2 = TextSegment.from("候选片段2 - 高度相关");
        TextSegment seg3 = TextSegment.from("候选片段3 - 不相关");

        Query query = Query.from("测试问题");
        when(mockDelegate.retrieve(query)).thenReturn(List.of(
                Content.from(seg1),
                Content.from(seg2),
                Content.from(seg3)
        ));

        // Reranker 判定 seg2 得分最高，seg1 次之
        when(mockReranker.rerank("测试问题", List.of(seg1, seg2, seg3), 2)).thenReturn(List.of(
                new RerankResult(seg2, 1, 0.95),
                new RerankResult(seg1, 0, 0.70)
        ));

        RerankingContentRetriever compositeRetriever = new RerankingContentRetriever(mockDelegate, mockReranker, 2);

        List<Content> contents = compositeRetriever.retrieve(query);

        assertThat(contents).hasSize(2);
        assertThat(contents.get(0).textSegment()).isEqualTo(seg2);
        assertThat(contents.get(1).textSegment()).isEqualTo(seg1);
    }

    @Test
    @DisplayName("RerankingContentRetriever: 当重排模型异常或超时时自动平滑降级为原始向量召回前 Top-K")
    void testRerankingContentRetrieverGracefulFallback() {
        ContentRetriever mockDelegate = mock(ContentRetriever.class);
        Reranker mockReranker = mock(Reranker.class);

        TextSegment seg1 = TextSegment.from("向量第1名");
        TextSegment seg2 = TextSegment.from("向量第2名");
        TextSegment seg3 = TextSegment.from("向量第3名");

        Query query = Query.from("问题");
        when(mockDelegate.retrieve(query)).thenReturn(List.of(
                Content.from(seg1),
                Content.from(seg2),
                Content.from(seg3)
        ));

        // 模拟 Reranker 发生超时或网络断开异常
        when(mockReranker.rerank(any(), any(), anyInt()))
                .thenThrow(new RuntimeException("Connect Timeout 5000ms"));

        RerankingContentRetriever compositeRetriever = new RerankingContentRetriever(mockDelegate, mockReranker, 2);

        // 不应抛出异常，而是优雅降级返回原始前 2 个片段
        List<Content> contents = compositeRetriever.retrieve(query);

        assertThat(contents).hasSize(2);
        assertThat(contents.get(0).textSegment()).isEqualTo(seg1);
        assertThat(contents.get(1).textSegment()).isEqualTo(seg2);
    }

    @Test
    @DisplayName("RerankingContentRetriever: 当重排模型因低于 minScore 正常返回空结果时，不应降级返回粗排结果，而是合规返回空列表")
    void testRerankingContentRetrieverEmptyResultsWhenBelowMinScore() {
        ContentRetriever mockDelegate = mock(ContentRetriever.class);
        Reranker mockReranker = mock(Reranker.class);

        TextSegment seg1 = TextSegment.from("不相关片段");
        Query query = Query.from("问题");
        when(mockDelegate.retrieve(query)).thenReturn(List.of(Content.from(seg1)));

        // Reranker 正常响应，但因为相关度不足过滤后为空
        when(mockReranker.rerank("问题", List.of(seg1), 2)).thenReturn(java.util.Collections.emptyList());

        RerankingContentRetriever compositeRetriever = new RerankingContentRetriever(mockDelegate, mockReranker, 2, 0.8);

        List<Content> contents = compositeRetriever.retrieve(query);

        assertThat(contents).isEmpty();
    }

    @Test
    @DisplayName("RerankResult: Comparable 降级排序与空安全验证")
    void testRerankResultComparable() {
        TextSegment seg = TextSegment.from("text");
        RerankResult r1 = new RerankResult(seg, 0, 0.9);
        RerankResult r2 = new RerankResult(seg, 1, 0.8);
        RerankResult rNull = new RerankResult(seg, 2, null);

        // r1 比 r2 得分高，降序排序时 r1 应该排在 r2 前面 (compareTo 返回负数)
        assertThat(r1.compareTo(r2)).isNegative();
        assertThat(r2.compareTo(r1)).isPositive();
        assertThat(r1.compareTo(r1)).isZero();
        assertThat(r1.compareTo(rNull)).isNegative();
    }
}
