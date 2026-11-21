package io.github.atengk.boot.sample.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.ai.cache.AiSemanticCache;
import io.github.atengk.boot.sample.ai.dto.AiCacheChatRequest;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 双级语义响应缓存控制器端到端集成测试 (100% 离线自洽 MockWebServer 驱动)
 *
 * @author Ateng
 * @since 2026-11-22
 */
@SpringBootTest(properties = {
        "ateng.boot.ai.cache.enabled=true",
        "ateng.boot.ai.cache.exact-enabled=true",
        "ateng.boot.ai.cache.semantic-enabled=true",
        "ateng.boot.ai.cache.similarity-threshold=0.95"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DisplayName("AI 双级语义响应缓存控制器端到端集成测试")
class CacheControllerIntegrationTest {

    private static MockWebServer server;
    private static final AtomicInteger CHAT_CALL_COUNTER = new AtomicInteger(0);

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
    private AiSemanticCache semanticCache;

    @BeforeAll
    static void startServer() throws IOException {
        CHAT_CALL_COUNTER.set(0);
        if (isLocalProfile()) {
            return;
        }
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() != null ? request.getPath() : "";
                String body = request.getBody().readUtf8();

                if (path.contains("/embeddings")) {
                    String vector = (body.contains("退款") || body.contains("退货"))
                            ? (body.contains("如何") ? "[0.99, 0.11, 0.0]" : "[0.98, 0.12, 0.0]")
                            : "[0.0, 0.0, 1.0]";
                    String embedJson = """
                            {
                              "object": "list",
                              "data": [
                                {
                                  "object": "embedding",
                                  "embedding": %s,
                                  "index": 0
                                }
                              ],
                              "model": "text-embedding-3-small",
                              "usage": {
                                "prompt_tokens": 5,
                                "total_tokens": 5
                              }
                            }
                            """.formatted(vector);
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json; charset=utf-8")
                            .setBody(embedJson);
                }

                int seq = CHAT_CALL_COUNTER.incrementAndGet();
                String chatJson = """
                        {
                          "id": "chatcmpl-cache-%d",
                          "object": "chat.completion",
                          "created": 1700000000,
                          "model": "gpt-4o",
                          "choices": [
                            {
                              "index": 0,
                              "message": {
                                "role": "assistant",
                                "content": "第%d次大模型生成：请前往订单中心申请售后。"
                              },
                              "finish_reason": "stop"
                            }
                          ],
                          "usage": {
                            "prompt_tokens": 12,
                            "completion_tokens": 18,
                            "total_tokens": 30
                          }
                        }
                        """.formatted(seq, seq);
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
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (server != null) {
            String baseUrl = server.url("/v1").toString();
            registry.add("ateng.boot.ai.model.base-url", () -> baseUrl);
            registry.add("ateng.boot.ai.model.api-key", () -> "sk-mock-cache-ci-key");
            registry.add("ateng.boot.ai.rag.model.base-url", () -> baseUrl);
            registry.add("ateng.boot.ai.rag.model.api-key", () -> "sk-mock-cache-ci-key");
        }
    }

    @AfterEach
    void cleanupCache() {
        if (semanticCache != null) {
            semanticCache.clearAll();
        }
        CHAT_CALL_COUNTER.set(0);
    }

    @Test
    @DisplayName("精确缓存与语义缓存双级命中、bypassCache 穿透、多租户隔离与驱逐清零")
    void shouldHitExactAndSemanticCacheIsolateTenantsAndEvictCleanly() throws Exception {
        // 1. 首次调用（tenantA）：未命中，穿透调用大模型（count=1）
        AiCacheChatRequest firstReq = new AiCacheChatRequest("怎么申请退款", null, "tenant-a", false);
        mockMvc.perform(post("/sample/ai/cache/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(firstReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.cacheHit").value(false))
                .andExpect(jsonPath("$.data.cacheSize").value(1));

        // 2. 相同提问（tenantA）：精确缓存命中，大模型调用计数仍为 1
        mockMvc.perform(post("/sample/ai/cache/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(firstReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.cacheHit").value(true));
        if (!isLocalProfile()) {
            assertThat(CHAT_CALL_COUNTER.get()).isEqualTo(1);
        }

        // 3. 语义相似提问（tenantA）：向量语义缓存命中
        AiCacheChatRequest semanticReq = new AiCacheChatRequest("请问如何办理退货", null, "tenant-a", false);
        mockMvc.perform(post("/sample/ai/cache/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(semanticReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.cacheHit").value(true));
        if (!isLocalProfile()) {
            assertThat(CHAT_CALL_COUNTER.get()).isEqualTo(1);
        }

        // 4. bypassCache=true 强制穿透大模型（count=2）
        AiCacheChatRequest bypassReq = new AiCacheChatRequest("怎么申请退款", null, "tenant-a", true);
        mockMvc.perform(post("/sample/ai/cache/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bypassReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.bypassed").value(true))
                .andExpect(jsonPath("$.data.cacheHit").value(false));
        if (!isLocalProfile()) {
            assertThat(CHAT_CALL_COUNTER.get()).isEqualTo(2);
        }

        // 5. 跨租户隔离（tenant-b 查询同一语义提问不会串用 tenant-a 缓存）
        AiCacheChatRequest tenantBReq = new AiCacheChatRequest("请问如何办理退货", null, "tenant-b", false);
        mockMvc.perform(post("/sample/ai/cache/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tenantBReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.cacheHit").value(false))
                .andExpect(jsonPath("$.data.cacheSize").value(2));

        // 6. 统计视图、按租户驱逐与全量清空
        mockMvc.perform(get("/sample/ai/cache/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.cacheSize").value(2));

        mockMvc.perform(delete("/sample/ai/cache/evict/tenant-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data").value(1));

        mockMvc.perform(delete("/sample/ai/cache/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data").value(0));
    }
}
