package io.github.atengk.boot.sample.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.ai.dto.AiSummaryChatRequest;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 长会话异步摘要记忆端到端集成测试 (100% 离线自洽)
 *
 * @author Ateng
 * @since 2026-11-05
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("AI 长会话异步摘要记忆端到端集成测试")
class SummaryControllerIntegrationTest {

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

    @BeforeAll
    static void startServer() throws IOException {
        if (isLocalProfile()) {
            return;
        }
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String chatJson = """
                        {
                          "id": "chatcmpl-summary-test-1",
                          "object": "chat.completion",
                          "created": 1700000000,
                          "model": "gpt-4o",
                          "choices": [
                            {
                              "index": 0,
                              "message": {
                                "role": "assistant",
                                "content": "收到，您的偏好和上下文信息已更新至长期会话状态。"
                              },
                              "finish_reason": "stop"
                            }
                          ],
                          "usage": {
                            "prompt_tokens": 20,
                            "completion_tokens": 15,
                            "total_tokens": 35
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
            registry.add("ateng.boot.ai.model.api-key", () -> "mock-test-key-summary-ci");
            registry.add("ateng.boot.ai.model.model-name", () -> "gpt-4o");
        }
    }

    @Test
    @DisplayName("端到端验证 POST /sample/ai/summary/chat, GET state 与 POST clear 全链路")
    void testSummaryChatMemoryFlow() throws Exception {
        String sessionId = "session_test_" + System.currentTimeMillis();

        // 1. 发起第一轮对话
        AiSummaryChatRequest req1 = AiSummaryChatRequest.builder()
                .sessionId(sessionId)
                .message("我的昵称是阿腾，我正在开发 Spring Boot 3 基础设施。")
                .build();

        mockMvc.perform(post("/sample/ai/summary/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sessionId").value(sessionId))
                .andExpect(jsonPath("$.data.reply").isNotEmpty());

        // 2. 查看当前双层状态
        mockMvc.perform(get("/sample/ai/summary/state")
                        .param("sessionId", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sessionId").value(sessionId))
                .andExpect(jsonPath("$.data.recentMessages").isArray())
                .andExpect(jsonPath("$.data.recentMessages").isNotEmpty());

        // 3. 清空会话状态
        mockMvc.perform(post("/sample/ai/summary/clear")
                        .param("sessionId", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 4. 清空后核验
        mockMvc.perform(get("/sample/ai/summary/state")
                        .param("sessionId", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.recentMessages").isEmpty());
    }
}
