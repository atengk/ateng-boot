package io.github.atengk.boot.sample.ai;

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
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 智能体多通道过程可观测控制器集成测试 (100% 离线自洽 MockWebServer 驱动)
 *
 * @author Ateng
 * @since 2026-11-06
 */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DisplayName("智能体多通道过程可观测控制器集成测试 (离线自洽)")
class AiAgentStreamingControllerIntegrationTest {

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

    @BeforeAll
    static void startServer() throws IOException {
        if (isLocalProfile()) {
            return;
        }
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String body = request.getBody().readUtf8();

                if (body.contains("<think>") || body.contains("reasoning") || body.contains("9.11") || body.contains("思考")) {
                    String sseReasoningBody = """
                            data: {"id":"chatcmpl-stream-r1","object":"chat.completion.chunk","created":1700000000,"model":"deepseek-reasoner","choices":[{"index":0,"delta":{"content":"<think>正在比对数字大小关系"},"finish_reason":null}]}
                            
                            data: {"id":"chatcmpl-stream-r2","object":"chat.completion.chunk","created":1700000000,"model":"deepseek-reasoner","choices":[{"index":0,"delta":{"content":"：9.11 小于 9.9</think>结论：9.9 大于 9.11。"},"finish_reason":null}]}
                            
                            data: {"id":"chatcmpl-stream-r3","object":"chat.completion.chunk","created":1700000000,"model":"deepseek-reasoner","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
                            
                            data: [DONE]
                            
                            """;
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "text/event-stream; charset=utf-8")
                            .setBody(sseReasoningBody);
                }

                String sseBody = """
                        data: {"id":"chatcmpl-stream-1","object":"chat.completion.chunk","created":1700000000,"model":"gpt-4o","choices":[{"index":0,"delta":{"role":"assistant","content":"订单审计核验流式输出中..."},"finish_reason":null}]}
                        
                        data: {"id":"chatcmpl-stream-2","object":"chat.completion.chunk","created":1700000000,"model":"gpt-4o","choices":[{"index":0,"delta":{"content":"合规核验完成。"},"finish_reason":null}]}
                        
                        data: {"id":"chatcmpl-stream-3","object":"chat.completion.chunk","created":1700000000,"model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
                        
                        data: [DONE]
                        
                        """;
                return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/event-stream; charset=utf-8")
                        .setBody(sseBody);
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
        if (isLocalProfile()) {
            return;
        }
        String baseUrl = "http://localhost:" + server.getPort() + "/v1";
        registry.add("ateng.boot.ai.model.base-url", () -> baseUrl);
        registry.add("ateng.boot.ai.model.api-key", () -> "mock-test-key-stream");
        registry.add("ateng.boot.ai.model.model-name", () -> "gpt-4o");
    }

    @Test
    @DisplayName("端到端验证 GET /sample/ai/agent/stream/audit 多通道过程打字机推流")
    void testAuditStreamSuccess() throws Exception {
        var mvcResult = mockMvc.perform(get("/sample/ai/agent/stream/audit")
                        .param("orderId", "ORD_STREAM_001")
                        .param("remarks", "多通道流式核查")
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 异步等待推流完成 (离线自洽 MockWebServer 5 秒超时，本地模型 30 秒超时)
        mvcResult.getAsyncResult(isLocalProfile() ? 30000 : 5000);

        String sseOutput = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(sseOutput).contains("event:text");
        assertThat(sseOutput).contains("event:done");
        assertThat(sseOutput).contains("data:[DONE]");
    }

    @Test
    @DisplayName("端到端验证 GET /sample/ai/agent/stream/audit 空订单号 JSR-303 快速失败")
    void testAuditStreamEmptyOrderIdFailFast() throws Exception {
        mockMvc.perform(get("/sample/ai/agent/stream/audit")
                        .param("orderId", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("端到端验证 GET /sample/ai/agent/stream/tools 动态工具推流")
    void testToolsStreamSuccess() throws Exception {
        var mvcResult = mockMvc.perform(get("/sample/ai/agent/stream/tools")
                        .param("prompt", "请查询 ORD_STREAM_001 详情")
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 异步等待推流完成 (离线自洽 MockWebServer 5 秒超时，本地模型 30 秒超时)
        mvcResult.getAsyncResult(isLocalProfile() ? 30000 : 5000);

        String sseOutput = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(sseOutput).contains("event:text");
        assertThat(sseOutput).contains("event:done");
    }

    @Test
    @DisplayName("端到端验证 GET /sample/ai/agent/stream/reasoning 思考链推流")
    void testReasoningStreamSuccess() throws Exception {
        var mvcResult = mockMvc.perform(get("/sample/ai/agent/stream/reasoning")
                        .param("prompt", "请思考：9.11 和 9.9 谁大？")
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 异步等待推流完成 (离线自洽 MockWebServer 5 秒超时，本地模型 30 秒超时)
        mvcResult.getAsyncResult(isLocalProfile() ? 30000 : 5000);

        String sseOutput = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(sseOutput).contains("event:reasoning");
        assertThat(sseOutput).contains("event:text");
        assertThat(sseOutput).contains("event:done");
    }
}
