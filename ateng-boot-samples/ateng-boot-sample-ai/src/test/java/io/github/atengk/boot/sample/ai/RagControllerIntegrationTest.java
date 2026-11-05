package io.github.atengk.boot.sample.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.github.atengk.boot.sample.ai.dto.AiRagAskRequest;
import io.github.atengk.boot.sample.ai.dto.AiRagEmbedRequest;
import io.github.atengk.boot.sample.ai.dto.AiRagIngestRequest;
import io.github.atengk.boot.sample.ai.dto.AiRagRerankRequest;
import io.github.atengk.boot.sample.ai.dto.AiRagRetrieveRequest;
import io.github.atengk.boot.sample.ai.dto.AiRagSplitRequest;
import io.github.atengk.boot.sample.ai.dto.AiRagTransformRequest;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI RAG 企业知识库与向量检索端到端集成测试 (100% 离线自洽 MockWebServer 驱动)
 *
 * @author Ateng
 * @since 2026-11-04
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("AI RAG 企业知识库与向量检索端到端集成测试")
class RagControllerIntegrationTest {

    private static MockWebServer server;

    private static boolean isLocalProfile() {
        String active = System.getProperty("spring.profiles.active");
        if (active == null) {
            active = System.getenv("SPRING_PROFILES_ACTIVE");
        }
        return active != null && active.contains("local");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private EmbeddingStore<TextSegment> embeddingStore;

    @BeforeAll
    static void startServer() throws IOException {
        if (isLocalProfile()) {
            return;
        }
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() != null ? request.getPath() : "";
                String body = request.getBody().readUtf8();

                // 1. 向量化 Embedding 接口模拟 (/embeddings)
                if (path.contains("/embeddings")) {
                    String embeddingJson;
                    if (body.contains("\"input\":[") && (body.indexOf("input\":[") != body.lastIndexOf("input\":["))) {
                        // 批量入参
                        embeddingJson = """
                                {
                                  "object": "list",
                                  "data": [
                                    {
                                      "object": "embedding",
                                      "embedding": [0.1, 0.2, 0.3, 0.4, 0.5],
                                      "index": 0
                                    },
                                    {
                                      "object": "embedding",
                                      "embedding": [0.2, 0.3, 0.4, 0.5, 0.6],
                                      "index": 1
                                    }
                                  ],
                                  "model": "text-embedding-3-small",
                                  "usage": {
                                    "prompt_tokens": 10,
                                    "total_tokens": 10
                                  }
                                }
                                """;
                    } else {
                        // 单条入参
                        embeddingJson = """
                                {
                                  "object": "list",
                                  "data": [
                                    {
                                      "object": "embedding",
                                      "embedding": [0.1, 0.2, 0.3, 0.4, 0.5],
                                      "index": 0
                                    }
                                  ],
                                  "model": "text-embedding-3-small",
                                  "usage": {
                                    "prompt_tokens": 5,
                                    "total_tokens": 5
                                  }
                                }
                                """;
                    }
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json; charset=utf-8")
                            .setBody(embeddingJson);
                }

                // 2. Chat Completions 对话与问答接口模拟
                String chatJson = """
                        {
                          "id": "chatcmpl-rag-test-1",
                          "object": "chat.completion",
                          "created": 1700000000,
                          "model": "gpt-4o",
                          "choices": [
                            {
                              "index": 0,
                              "message": {
                                "role": "assistant",
                                "content": "根据知识库资料，ateng-boot 遵循纯原生零臃肿架构设计，严禁泄露持久化实体。"
                              },
                              "finish_reason": "stop"
                            }
                          ],
                          "usage": {
                            "prompt_tokens": 40,
                            "completion_tokens": 25,
                            "total_tokens": 65
                          }
                        }
                        """;
                return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json; charset=utf-8")
                        .setBody(chatJson);
            }
        });
        server.start();
    }

    @AfterAll
    static void stopServer() throws IOException {
        if (server != null) {
            server.shutdown();
        }
    }

    @DynamicPropertySource
    static void registerAiProperties(DynamicPropertyRegistry registry) {
        if (!isLocalProfile()) {
            registry.add("ateng.boot.ai.model.base-url", () -> server.url("/v1").toString());
            registry.add("ateng.boot.ai.model.api-key", () -> "mock-test-key-rag-ci");
            registry.add("ateng.boot.ai.model.model-name", () -> "gpt-4o");
            registry.add("ateng.boot.ai.rag.enabled", () -> "true");
            registry.add("ateng.boot.ai.rag.max-results", () -> "3");
            registry.add("ateng.boot.ai.rag.min-score", () -> "0.5");
        }
    }

    @Test
    @DisplayName("验证 POST /sample/ai/rag/embed 文本特征向量化生成成功")
    void testEmbedSuccess() throws Exception {
        Assumptions.assumeTrue(!isLocalProfile(), "本地未部署物理 Embedding 向量模型，触发 [豁免：无可用环境] 规范");
        AiRagEmbedRequest request = AiRagEmbedRequest.builder()
                .text("ateng-boot 核心研发架构指南")
                .build();

        mockMvc.perform(post("/sample/ai/rag/embed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.dimension").value(5))
                .andExpect(jsonPath("$.data.preview").isArray())
                .andExpect(jsonPath("$.data.summary").isNotEmpty());
    }

    @Test
    @DisplayName("验证 POST /sample/ai/rag/embed 空白文本参数校验防御 (JSR-303 Fail-Fast)")
    void testEmbedValidationFail() throws Exception {
        AiRagEmbedRequest request = AiRagEmbedRequest.builder()
                .text("   ")
                .build();

        mockMvc.perform(post("/sample/ai/rag/embed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("端到端验证 POST /sample/ai/rag/ingest 知识入库, retrieve 检索召回与 ask 增强问答全链路")
    void testEndToEndRagFlow() throws Exception {
        Assumptions.assumeTrue(!isLocalProfile(), "本地未部署物理 Embedding 向量模型，触发 [豁免：无可用环境] 规范");
        // 1. 知识库切片入库
        AiRagIngestRequest ingestRequest = AiRagIngestRequest.builder()
                .text("ateng-boot 遵循纯原生零臃肿架构设计，Controller 传输边界 100% 严禁接收或直接返回 ORM 实体。")
                .metadata(Map.of("category", "architecture", "author", "Ateng"))
                .build();

        mockMvc.perform(post("/sample/ai/rag/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ingestRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.text").value(ingestRequest.getText()))
                .andExpect(jsonPath("$.data.metadata.category").value("architecture"));

        // 2. 向量相似度检索召回
        AiRagRetrieveRequest retrieveRequest = AiRagRetrieveRequest.builder()
                .query("Controller 传输边界设计规范")
                .build();

        mockMvc.perform(post("/sample/ai/rag/retrieve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(retrieveRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.query").value(retrieveRequest.getQuery()))
                .andExpect(jsonPath("$.data.matches").isArray())
                .andExpect(jsonPath("$.data.matches[0].text").value(ingestRequest.getText()));

        // 3. RAG 知识库融合问答
        AiRagAskRequest askRequest = AiRagAskRequest.builder()
                .question("ateng-boot 的 Controller 层能直接返回 Entity 吗？")
                .build();

        mockMvc.perform(post("/sample/ai/rag/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(askRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.question").value(askRequest.getQuestion()))
                .andExpect(jsonPath("$.data.answer").value("根据知识库资料，ateng-boot 遵循纯原生零臃肿架构设计，严禁泄露持久化实体。"))
                .andExpect(jsonPath("$.data.referenceContexts[0]").value(ingestRequest.getText()));
    }

    @Test
    @DisplayName("验证 POST /sample/ai/rag/split 递归字符分块与 Markdown 标题切分")
    void testSplitSuccess() throws Exception {
        // 1. 递归字符切片策略验证
        AiRagSplitRequest recursiveReq = AiRagSplitRequest.builder()
                .text("ateng-boot 坚持纯原生、零臃肿依赖与显式强类型契约设计。Controller 统一显式声明返回强类型 Result<T>。数据安全遵循内存真明文不变量，持久层与传输层彻底解耦。")
                .strategy("RECURSIVE")
                .chunkSize(40)
                .chunkOverlap(10)
                .build();

        mockMvc.perform(post("/sample/ai/rag/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(recursiveReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalSegments").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.data.strategy").value("RECURSIVE"))
                .andExpect(jsonPath("$.data.segments").isArray());

        // 2. Markdown 层级标题切片策略验证
        String mdContent = """
                # 系统概览
                这是系统概览核心内容。
                ## 架构规范
                遵循 Spring Boot 3 工业级基础设施基线。
                """;
        AiRagSplitRequest mdReq = AiRagSplitRequest.builder()
                .text(mdContent)
                .strategy("MARKDOWN")
                .chunkSize(200)
                .chunkOverlap(20)
                .build();

        mockMvc.perform(post("/sample/ai/rag/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mdReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.strategy").value("MARKDOWN"))
                .andExpect(jsonPath("$.data.totalSegments").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    @DisplayName("验证 POST /sample/ai/rag/rerank 候选切片两阶段交叉重排")
    void testRerankSuccess() throws Exception {
        AiRagRerankRequest rerankReq = AiRagRerankRequest.builder()
                .query("Spring Boot Controller 契约")
                .candidates(java.util.List.of(
                        "Redis 缓存穿透防卫与分布式锁",
                        "Spring Boot Controller 统一显式声明返回强类型 Result 契约",
                        "MyBatis-Plus 分页插件自动分页"
                ))
                .topK(2)
                .build();

        mockMvc.perform(post("/sample/ai/rag/rerank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rerankReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.topK").value(2))
                .andExpect(jsonPath("$.data.results").isArray())
                .andExpect(jsonPath("$.data.results[0].text").value("Spring Boot Controller 统一显式声明返回强类型 Result 契约"))
                .andExpect(jsonPath("$.data.results[0].rank").value(1));
    }

    @Test
    @DisplayName("验证 POST /sample/ai/rag/transform 多轮指代消解查询改写")
    void testTransformSuccess() throws Exception {
        AiRagTransformRequest request = AiRagTransformRequest.builder()
                .query("它在 Spring Boot 3 中有哪些自动装配改进？")
                .history(java.util.List.of(
                        "用户：什么是 LangChain4j？",
                        "助手：LangChain4j 是 Java 生态流行的大语言模型应用开发框架。"
                ))
                .build();

        mockMvc.perform(post("/sample/ai/rag/transform")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.originalQuery").value("它在 Spring Boot 3 中有哪些自动装配改进？"))
                .andExpect(jsonPath("$.data.transformedQuery").isNotEmpty())
                .andExpect(jsonPath("$.data.historyCount").value(2));
    }
}
