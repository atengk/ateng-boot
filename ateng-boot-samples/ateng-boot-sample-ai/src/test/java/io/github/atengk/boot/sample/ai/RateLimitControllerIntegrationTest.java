package io.github.atengk.boot.sample.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.ai.dto.AiRateLimitChatRequest;
import io.github.atengk.boot.sample.ai.dto.AiRateLimitRequest;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 微观突发滑动限流与配额防卫端到端集成测试 (100% 离线自洽双模驱动)
 *
 * @author Ateng
 * @since 2026-11-05
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("AI 微观突发滑动限流端到端集成测试")
class RateLimitControllerIntegrationTest {

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
                          "id": "chatcmpl-ratelimit-test",
                          "object": "chat.completion",
                          "created": 1700000000,
                          "model": "gpt-4o",
                          "choices": [
                            {
                              "index": 0,
                              "message": {
                                "role": "assistant",
                                "content": "您好！这是受控限流保护下的测试回答。"
                              },
                              "finish_reason": "stop"
                            }
                          ],
                          "usage": {
                            "prompt_tokens": 15,
                            "completion_tokens": 20,
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
            registry.add("ateng.boot.ai.model.api-key", () -> "mock-test-key-ratelimit-ci");
            registry.add("ateng.boot.ai.model.model-name", () -> "gpt-4o");
        }
    }

    @Test
    @DisplayName("验证 POST /sample/ai/ratelimit/try-acquire 正常申请流量配额通过")
    void testTryAcquireSuccess() throws Exception {
        AiRateLimitRequest request = AiRateLimitRequest.builder()
                .apiKey("sk-test-sample-key")
                .tenantId("tenant-enterprise")
                .modelName("gpt-4o")
                .tokens(50)
                .build();

        mockMvc.perform(post("/sample/ai/ratelimit/try-acquire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.allowed").isBoolean())
                .andExpect(jsonPath("$.data.clientKey").value("tenant-enterprise"))
                .andExpect(jsonPath("$.data.message").isNotEmpty());
    }

    @Test
    @DisplayName("验证 JSR-303 防卫校验：非法入参 (Token <= 0) 触发 400 业务码校验拦截")
    void testTryAcquireValidationFailure() throws Exception {
        AiRateLimitRequest invalidRequest = AiRateLimitRequest.builder()
                .modelName("")
                .tokens(-10)
                .build();

        mockMvc.perform(post("/sample/ai/ratelimit/try-acquire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("验证 POST /sample/ai/ratelimit/chat 受控限流大模型对话")
    void testRateLimitedChatSuccess() throws Exception {
        AiRateLimitChatRequest request = AiRateLimitChatRequest.builder()
                .tenantId("tenant-chat-rate")
                .modelName("gpt-4o")
                .prompt("你好，请做自我介绍。")
                .estimatedTokens(60)
                .build();

        var actions = mockMvc.perform(post("/sample/ai/ratelimit/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.prompt").value("你好，请做自我介绍。"))
                .andExpect(jsonPath("$.data.allowed").value(true));

        if (!isLocalProfile()) {
            actions.andExpect(jsonPath("$.data.reply").value("您好！这是受控限流保护下的测试回答。"));
        } else {
            actions.andExpect(jsonPath("$.data.reply").isString());
        }
    }
}
