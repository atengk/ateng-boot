package io.github.atengk.boot.ai.rag.rerank;

import dev.langchain4j.data.segment.TextSegment;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.core.util.JsonUtil;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * 基于标准 OpenAI/REST 兼容协议的重排模型客户端实现
 * <p>
 * 适配 SiliconFlow (硅基流动)、DashScope (通义千问)、HuggingFace TEI (Text Embeddings Inference)、Cohere 等
 * 业界通用 Rerank REST API 契约，以纯原生 JDK 17 {@link HttpClient} 通信，零引入第三方私有重排 SDK。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class OpenAiCompatibleReranker implements Reranker {

    public static final String DEFAULT_MODEL = "BAAI/bge-reranker-v2-m3";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final Duration timeout;
    private final Double minScore;
    private final HttpClient httpClient;

    public OpenAiCompatibleReranker(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, DEFAULT_MODEL, DEFAULT_TIMEOUT, null, null);
    }

    public OpenAiCompatibleReranker(String baseUrl, String apiKey, String modelName, Duration timeout, Double minScore) {
        this(baseUrl, apiKey, modelName, timeout, minScore, null);
    }

    public OpenAiCompatibleReranker(String baseUrl, String apiKey, String modelName, Duration timeout, Double minScore, HttpClient httpClient) {
        Assert.hasText(baseUrl, "Rerank baseUrl 不能为空");
        this.baseUrl = normalizeEndpoint(baseUrl);
        this.apiKey = apiKey;
        this.modelName = StringUtils.hasText(modelName) ? modelName : DEFAULT_MODEL;
        this.timeout = timeout != null ? timeout : DEFAULT_TIMEOUT;
        this.minScore = minScore;
        this.httpClient = httpClient != null ? httpClient : HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .build();
    }

    private static String normalizeEndpoint(String url) {
        String trimmed = url.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (!trimmed.endsWith("/rerank")) {
            trimmed = trimmed + "/rerank";
        }
        return trimmed;
    }

    @Override
    public List<RerankResult> rerank(String query, List<TextSegment> segments, int topK) {
        if (!StringUtils.hasText(query) || segments == null || segments.isEmpty() || topK <= 0) {
            return Collections.emptyList();
        }

        if (segments.size() == 1) {
            return List.of(new RerankResult(segments.get(0), 0, 1.0));
        }

        List<String> documents = segments.stream()
                .map(TextSegment::text)
                .toList();

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", modelName);
        requestBody.put("query", query);
        requestBody.put("documents", documents);
        requestBody.put("top_n", topK);

        String jsonPayload = JsonUtil.toJsonString(requestBody);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload));

        if (StringUtils.hasText(apiKey)) {
            requestBuilder.header("Authorization", "Bearer " + apiKey.trim());
        }

        try {
            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            if (statusCode == 401 || statusCode == 403) {
                throw new AiException(AiResultCode.AI_AUTH_FAILED,
                        String.format("调用外部 Rerank 服务鉴权失败 [status=%d, url=%s, body=%s]",
                                statusCode, baseUrl, response.body()));
            }
            if (statusCode == 429) {
                throw new AiException(AiResultCode.AI_RATE_LIMIT_EXCEEDED,
                        String.format("调用外部 Rerank 服务触发限流 [status=%d, url=%s, body=%s]",
                                statusCode, baseUrl, response.body()));
            }
            if (statusCode < 200 || statusCode >= 300) {
                throw new AiException(AiResultCode.AI_CALL_FAILED,
                        String.format("调用外部 Rerank 服务异常 [status=%d, url=%s, body=%s]",
                                statusCode, baseUrl, response.body()));
            }

            return parseRerankResponse(response.body(), segments, topK);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new AiException(AiResultCode.AI_TIMEOUT, "调用外部 Rerank 服务超时: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new AiException(AiResultCode.AI_CALL_FAILED, "调用外部 Rerank 服务网络 IO 失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException(AiResultCode.AI_CALL_FAILED, "调用外部 Rerank 服务被中断: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<RerankResult> parseRerankResponse(String responseBody, List<TextSegment> originalSegments, int topK) {
        if (!StringUtils.hasText(responseBody)) {
            return Collections.emptyList();
        }

        Map<String, Object> parsed = JsonUtil.parseObject(responseBody, Map.class);
        if (parsed == null || !parsed.containsKey("results")) {
            return Collections.emptyList();
        }

        List<?> rawResults = (List<?>) parsed.get("results");
        if (rawResults == null || rawResults.isEmpty()) {
            return Collections.emptyList();
        }

        List<RerankResult> results = new ArrayList<>();
        for (Object item : rawResults) {
            if (!(item instanceof Map<?, ?> itemMap)) {
                continue;
            }

            Integer index = null;
            Object indexObj = itemMap.get("index");
            if (indexObj instanceof Number num) {
                index = num.intValue();
            } else if (itemMap.get("document_index") instanceof Number docNum) {
                index = docNum.intValue();
            }

            Double score = null;
            Object scoreObj = itemMap.get("relevance_score");
            if (scoreObj instanceof Number num) {
                score = num.doubleValue();
            } else if (itemMap.get("score") instanceof Number sNum) {
                score = sNum.doubleValue();
            }

            if (index != null && index >= 0 && index < originalSegments.size()) {
                if (minScore != null && (score == null || score < minScore)) {
                    continue;
                }
                results.add(new RerankResult(originalSegments.get(index), index, score != null ? score : 0.0));
            }
        }

        // 降序排序并截取 topK
        results.sort(Comparator.naturalOrder());
        if (results.size() > topK) {
            return Collections.unmodifiableList(results.subList(0, topK));
        }
        return Collections.unmodifiableList(results);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getModelName() {
        return modelName;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public Double getMinScore() {
        return minScore;
    }
}
