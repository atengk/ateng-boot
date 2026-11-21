package io.github.atengk.boot.sample.ai;

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
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * 流式输出 REPLACE 护栏脱敏替换端到端集成测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("AI 流式护栏 REPLACE 替换事件端到端集成测试")
class GuardrailStreamingReplaceIntegrationTest {

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
                String sse = "data: {\"id\":\"chatcmpl-rep-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"合规片段：\"},\"finish_reason\":null}]}\n\n"
                        + "data: {\"id\":\"chatcmpl-rep-2\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"违禁品测试\"},\"finish_reason\":null}]}\n\n"
                        + "data: {\"id\":\"chatcmpl-rep-3\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"尾巴不应出现\"},\"finish_reason\":null}]}\n\n"
                        + "data: {\"id\":\"chatcmpl-rep-4\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n";
                return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/event-stream; charset=utf-8")
                        .setBody(sse);
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
            registry.add("ateng.boot.ai.model.api-key", () -> "mock-test-key-replace-ci");
            registry.add("ateng.boot.ai.model.model-name", () -> "gpt-4o");
            registry.add("ateng.boot.ai.guardrail.mode", () -> "REPLACE");
        }
    }

    @Test
    @DisplayName("端到端验证 GET /sample/ai/chat/stream REPLACE 模式实时推送 guardrail_replaced 并完结流")
    void testChatStreamGuardrailReplacedEvent() throws Exception {
        Assumptions.assumeFalse(isLocalProfile());
        var mvcResult = mockMvc.perform(get("/sample/ai/chat/stream")
                        .param("prompt", "请测试流式替换护栏")
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvcResult.getAsyncResult(5000);
        String sseOutput = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(sseOutput).contains("合规片段");
        assertThat(sseOutput).contains("event:guardrail_replaced");
        assertThat(sseOutput).contains("event:done");
        assertThat(sseOutput).contains("data:[DONE]");
        assertThat(sseOutput).doesNotContain("违禁品测试");
        assertThat(sseOutput).doesNotContain("尾巴不应出现");
    }

    @Test
    @DisplayName("端到端验证 GET /sample/ai/agent/audit/stream REPLACE 模式实时推送 guardrail_replaced 并完结流")
    void testAgentStreamGuardrailReplacedEvent() throws Exception {
        Assumptions.assumeFalse(isLocalProfile());
        var mvcResult = mockMvc.perform(get("/sample/ai/agent/audit/stream")
                        .param("orderId", "ORD_REPLACE_001")
                        .param("remarks", "合规审计替换测试")
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvcResult.getAsyncResult(5000);
        String sseOutput = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(sseOutput).contains("合规片段");
        assertThat(sseOutput).contains("event:guardrail_replaced");
        assertThat(sseOutput).contains("event:done");
        assertThat(sseOutput).contains("data:[DONE]");
        assertThat(sseOutput).doesNotContain("违禁品测试");
        assertThat(sseOutput).doesNotContain("尾巴不应出现");
    }
}
