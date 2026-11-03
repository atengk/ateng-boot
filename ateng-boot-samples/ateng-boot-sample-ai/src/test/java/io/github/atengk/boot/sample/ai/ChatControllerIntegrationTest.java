package io.github.atengk.boot.sample.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.ai.dto.AiChatRequestDto;
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
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 控制器端到端集成测试 (100% 离线自洽 MockWebServer 驱动)
 *
 * @author Ateng
 * @since 2026-11-03
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("AI 对话场景端到端集成测试 (离线自洽双模驱动)")
class ChatControllerIntegrationTest {

    private static MockWebServer server;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeAll
    static void startServer() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String body = request.getBody().readUtf8();
                String accept = request.getHeader("Accept");

                // 判断是否为流式请求 (检查 Accept 请求头或 JSON 中的 stream 字段)
                boolean isStream = (accept != null && accept.contains("text/event-stream"))
                        || body.contains("\"stream\":true")
                        || body.contains("\"stream\": true")
                        || body.contains("\"stream\"");

                if (isStream) {
                    String sseBody = """
                            data: {"id":"chatcmpl-stream-1","object":"chat.completion.chunk","created":1700000000,"model":"gpt-4o","choices":[{"index":0,"delta":{"role":"assistant","content":"<think>正在深入推导"},"finish_reason":null}]}
                            
                            data: {"id":"chatcmpl-stream-2","object":"chat.completion.chunk","created":1700000000,"model":"gpt-4o","choices":[{"index":0,"delta":{"content":"</think>你好，这是最终答复。"},"finish_reason":null}]}
                            
                            data: {"id":"chatcmpl-stream-3","object":"chat.completion.chunk","created":1700000000,"model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
                            
                            data: [DONE]
                            
                            """;
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "text/event-stream; charset=utf-8")
                            .setBody(sseBody);
                }

                // 默认同步响应
                String syncJson = """
                        {
                          "id": "chatcmpl-sync-test",
                          "object": "chat.completion",
                          "created": 1700000000,
                          "model": "gpt-4o",
                          "choices": [
                            {
                              "index": 0,
                              "message": {
                                "role": "assistant",
                                "content": "这是 MockWebServer 响应的同步回答。"
                              },
                              "finish_reason": "stop"
                            }
                          ],
                          "usage": {
                            "prompt_tokens": 10,
                            "completion_tokens": 20,
                            "total_tokens": 30
                          }
                        }
                        """;
                return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json; charset=utf-8")
                        .setBody(syncJson);
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
        registry.add("ateng.boot.ai.model.base-url", () -> server.url("/v1").toString());
        registry.add("ateng.boot.ai.model.api-key", () -> "mock-test-key-ci");
        registry.add("ateng.boot.ai.model.model-name", () -> "gpt-4o");
        registry.add("ateng.boot.ai.models.deepseek.base-url", () -> server.url("/v1").toString());
        registry.add("ateng.boot.ai.models.deepseek.api-key", () -> "mock-test-key-ci");
        registry.add("ateng.boot.ai.models.deepseek.model-name", () -> "deepseek-reasoner");
    }

    @Test
    @DisplayName("端到端验证 POST /sample/ai/chat/sync 同步对话成功")
    void testSyncChatSuccess() throws Exception {
        AiChatRequestDto request = AiChatRequestDto.builder()
                .prompt("你好，请介绍一下你自己")
                .build();

        mockMvc.perform(post("/sample/ai/chat/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.reply").value("这是 MockWebServer 响应的同步回答。"))
                .andExpect(jsonPath("$.data.model").value("default"));
    }

    @Test
    @DisplayName("端到端验证 POST /sample/ai/chat/sync 指定命名模型对话")
    void testSyncChatWithNamedModel() throws Exception {
        AiChatRequestDto request = AiChatRequestDto.builder()
                .prompt("求解高阶微积分难题")
                .model("deepseek")
                .build();

        mockMvc.perform(post("/sample/ai/chat/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.reply").value("这是 MockWebServer 响应的同步回答。"))
                .andExpect(jsonPath("$.data.model").value("deepseek"));
    }

    @Test
    @DisplayName("端到端验证 POST /sample/ai/chat/sync 图文多模态分析")
    void testSyncChatMultimodal() throws Exception {
        AiChatRequestDto request = AiChatRequestDto.builder()
                .prompt("识别发票总金额")
                .images(Collections.singletonList("https://example.com/invoice.png"))
                .build();

        mockMvc.perform(post("/sample/ai/chat/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.reply").value("这是 MockWebServer 响应的同步回答。"));
    }

    @Test
    @DisplayName("端到端验证 POST /sample/ai/chat/sync 空提示词快速失败拦截")
    void testSyncChatEmptyPromptFailFast() throws Exception {
        AiChatRequestDto request = AiChatRequestDto.builder()
                .prompt("   ")
                .build();

        mockMvc.perform(post("/sample/ai/chat/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("端到端验证 GET /sample/ai/chat/stream 双通道打字机流式推流")
    void testStreamChatSuccess() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/ai/chat/stream")
                        .param("prompt", "请流式输出回答"))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 异步等待推流完成
        mvcResult.getAsyncResult(5000);

        String sseOutput = mvcResult.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        // 验证 reasoning 与 text 双通道事件及 [DONE] 标志
        assertThat(sseOutput).contains("event:reasoning");
        assertThat(sseOutput).contains("data:正在深入推导");
        assertThat(sseOutput).contains("event:text");
        assertThat(sseOutput).contains("data:你好，这是最终答复。");
        assertThat(sseOutput).contains("event:done");
        assertThat(sseOutput).contains("data:[DONE]");
    }

    @Test
    @DisplayName("端到端验证 GET /sample/ai/chat/stream 空提示词快速失败拦截")
    void testStreamChatEmptyPromptFailFast() throws Exception {
        mockMvc.perform(get("/sample/ai/chat/stream")
                        .param("prompt", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }
}
