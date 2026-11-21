package io.github.atengk.boot.sample.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.ai.dto.FinancialAuditRequest;
import io.github.atengk.boot.sample.ai.dto.UserAnalysisRequestDto;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 核心加固治理端到端集成测试（原生 JSON Schema 协议注入、@AiAgent POJO 前置护栏、流式实时后置阻断）
 *
 * @author Ateng
 * @since 2026-11-21
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("AI 加固治理全链路端到端集成测试")
class HardeningGovernanceIntegrationTest {

    private static MockWebServer server;
    private static final List<String> CAPTURED_BODIES = new CopyOnWriteArrayList<>();

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
                String body = request.getBody().readUtf8();
                CAPTURED_BODIES.add(body);

                String accept = request.getHeader("Accept");
                boolean isStream = (accept != null && accept.contains("text/event-stream"))
                        || body.contains("\"stream\":true")
                        || body.contains("\"stream\": true")
                        || body.contains("\"stream\"");
                if (isStream) {
                    String sse = "data: {\"id\":\"chatcmpl-guard-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"合规分析开头：\"},\"finish_reason\":null}]}\n\n"
                            + "data: {\"id\":\"chatcmpl-guard-2\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"发现违禁品线索\"},\"finish_reason\":null}]}\n\n"
                            + "data: {\"id\":\"chatcmpl-guard-3\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"不应送达的后续内容\"},\"finish_reason\":null}]}\n\n"
                            + "data: {\"id\":\"chatcmpl-guard-4\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                            + "data: [DONE]\n\n";
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "text/event-stream; charset=utf-8")
                            .setBody(sse);
                }

                String contentJson;
                if (body.contains("UserAnalysisVo") || body.contains("intentLevel")) {
                    contentJson = "{\\\"name\\\":\\\"张三\\\",\\\"intentLevel\\\":5,\\\"tags\\\":[\\\"高意向\\\",\\\"架构咨询\\\"],\\\"summary\\\":\\\"强意愿企业架构客户\\\"}";
                } else {
                    contentJson = "{\\\"status\\\":\\\"APPROVED\\\",\\\"analysis\\\":\\\"交易合规，无异常特征。\\\",\\\"riskScore\\\":12,\\\"riskTags\\\":[\\\"LOW_RISK\\\"]}";
                }
                String chatJson = """
                        {
                          "id": "chatcmpl-hardening-test",
                          "object": "chat.completion",
                          "created": 1700000000,
                          "model": "gpt-4o",
                          "choices": [
                            {
                              "index": 0,
                              "message": {
                                "role": "assistant",
                                "content": "%s"
                              },
                              "finish_reason": "stop"
                            }
                          ],
                          "usage": {
                            "prompt_tokens": 20,
                            "completion_tokens": 30,
                            "total_tokens": 50
                          }
                        }
                        """.formatted(contentJson);
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

    @BeforeEach
    void clearCapturedBodies() {
        CAPTURED_BODIES.clear();
    }

    @DynamicPropertySource
    static void registerAiProperties(DynamicPropertyRegistry registry) {
        if (!isLocalProfile()) {
            registry.add("ateng.boot.ai.model.base-url", () -> server.url("/v1").toString());
            registry.add("ateng.boot.ai.model.api-key", () -> "mock-test-key-hardening-ci");
            registry.add("ateng.boot.ai.model.model-name", () -> "gpt-4o");
            registry.add("ateng.boot.ai.model.strict-native-json-schema", () -> "true");
            registry.add("ateng.boot.ai.guardrail.mode", () -> "FAIL_FAST");
        }
    }

    @Test
    @DisplayName("端到端验证 POST /sample/ai/chat/extract 注入原生 response_format.json_schema")
    void testChatForObjectNativeJsonSchemaInjection() throws Exception {
        Assumptions.assumeFalse(isLocalProfile());
        UserAnalysisRequestDto request = new UserAnalysisRequestDto("张三", "咨询高可用分布式事务与微服务底座方案", null);

        mockMvc.perform(post("/sample/ai/chat/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.name").value("张三"))
                .andExpect(jsonPath("$.data.intentLevel").value(5));

        assertThat(CAPTURED_BODIES).hasSize(1);
        JsonNode root = objectMapper.readTree(CAPTURED_BODIES.get(0));
        assertThat(root.path("response_format").path("type").asText()).isEqualTo("json_schema");
        JsonNode jsonSchema = root.path("response_format").path("json_schema");
        assertThat(jsonSchema.path("name").asText()).isEqualTo("UserAnalysisVo");
        assertThat(jsonSchema.path("strict").asBoolean()).isTrue();
        assertThat(jsonSchema.path("schema").path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("端到端验证 POST /sample/ai/agent/audit 智能体结构化输出注入原生 json_schema")
    void testAiAgentNativeJsonSchemaInjection() throws Exception {
        Assumptions.assumeFalse(isLocalProfile());
        FinancialAuditRequest request = new FinancialAuditRequest("ORD_SCHEMA_001", "正常服务器采购费用报销");

        mockMvc.perform(post("/sample/ai/agent/audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        assertThat(CAPTURED_BODIES).hasSize(1);
        JsonNode root = objectMapper.readTree(CAPTURED_BODIES.get(0));
        assertThat(root.path("response_format").path("type").asText()).isEqualTo("json_schema");
        JsonNode jsonSchema = root.path("response_format").path("json_schema");
        assertThat(jsonSchema.path("name").asText()).isEqualTo("FinancialAuditResult");
        assertThat(jsonSchema.path("strict").asBoolean()).isTrue();
        assertThat(jsonSchema.path("schema").path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("端到端验证 POST /sample/ai/agent/audit 复杂 POJO 入参触发前置护栏拦截且不消耗大模型请求")
    void testAiAgentPojoPrePromptGuardrailBlocked() throws Exception {
        Assumptions.assumeFalse(isLocalProfile());
        FinancialAuditRequest request = new FinancialAuditRequest("ORD_BLOCK_001", "采购违禁品专项审批");

        mockMvc.perform(post("/sample/ai/agent/audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("61018"));

        assertThat(CAPTURED_BODIES).isEmpty();
    }

    @Test
    @DisplayName("端到端验证 GET /sample/ai/agent/audit/stream 流式后置护栏实时拦截并阻止后续违禁块输出")
    void testAiAgentStreamingPostGuardrailRealTimeAbort() throws Exception {
        Assumptions.assumeFalse(isLocalProfile());
        var mvcResult = mockMvc.perform(get("/sample/ai/agent/audit/stream")
                        .param("orderId", "ORD_STREAM_GUARD_001")
                        .param("remarks", "正常合规审计流式检查")
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvcResult.getAsyncResult(5000);
        String sseOutput = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(sseOutput).contains("合规分析开头");
        assertThat(sseOutput).contains("event:error");
        assertThat(sseOutput).contains("大模型回复内容包含违规敏感词");
        assertThat(sseOutput).doesNotContain("发现违禁品线索");
        assertThat(sseOutput).doesNotContain("不应送达的后续内容");
        assertThat(sseOutput).doesNotContain("data:[DONE]");
    }
}
