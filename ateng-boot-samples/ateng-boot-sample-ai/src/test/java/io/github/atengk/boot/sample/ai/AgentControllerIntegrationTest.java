package io.github.atengk.boot.sample.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.ai.memory.ChatMemoryManager;
import io.github.atengk.boot.sample.ai.dto.FinancialAuditRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 声明式智能体与沙箱集成测试 (100% 离线自洽 MockWebServer 驱动)
 *
 * @author Ateng
 * @since 2026-11-03
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("AI 声明式智能体与沙箱集成测试 (离线自洽双模驱动)")
class AgentControllerIntegrationTest {

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
    private ChatMemoryManager chatMemoryManager;

    @org.junit.jupiter.api.BeforeEach
    @org.junit.jupiter.api.AfterEach
    void resetState() {
        if (chatMemoryManager != null) {
            chatMemoryManager.clear("default");
            chatMemoryManager.clear("sess_audit_test_001");
        }
    }

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
                String accept = request.getHeader("Accept");

                // 流式请求识别 (检查 Accept 请求头或 JSON 中的 stream 标识)
                boolean isStream = (accept != null && accept.contains("text/event-stream"))
                        || body.contains("\"stream\":true")
                        || body.contains("\"stream\": true")
                        || body.contains("\"stream\"");

                if (isStream) {
                    String sseBody = """
                            data: {"id":"chatcmpl-agent-stream-1","object":"chat.completion.chunk","created":1700000000,"model":"gpt-4o","choices":[{"index":0,"delta":{"role":"assistant","content":"订单审计核验中..."},"finish_reason":null}]}
                            
                            data: {"id":"chatcmpl-agent-stream-2","object":"chat.completion.chunk","created":1700000000,"model":"gpt-4o","choices":[{"index":0,"delta":{"content":"财务合规通过。"},"finish_reason":null}]}
                            
                            data: {"id":"chatcmpl-agent-stream-3","object":"chat.completion.chunk","created":1700000000,"model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
                            
                            data: [DONE]
                            
                            """;
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "text/event-stream; charset=utf-8")
                            .setBody(sseBody);
                }

                // 1. 触发 JSR-303 越界异常场景 (大模型输出 150 评分违反 @Max(100))
                if (body.contains("ORD_VIOLATION_SCORE")) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json; charset=utf-8")
                            .setBody("""
                                    {
                                      "id": "chatcmpl-viol-1",
                                      "object": "chat.completion",
                                      "created": 1700000000,
                                      "model": "gpt-4o",
                                      "choices": [
                                        {
                                          "index": 0,
                                          "message": {
                                            "role": "assistant",
                                            "content": "{\\"status\\":\\"APPROVED\\",\\"analysis\\":\\"大模型违规超分\\",\\"riskScore\\":150,\\"riskTags\\":[]}"
                                          },
                                          "finish_reason": "stop"
                                        }
                                      ]
                                    }
                                    """);
                }

                // 2. 工具执行结果返回后的第二轮决策输出
                if (body.contains("tool_call_id") || body.contains("\"role\":\"tool\"") || body.contains("\"role\": \"tool\"")) {
                    // 异常自愈分支：检测到 OrderTools 抛出的 NOT_FOUND 业务拦截异常
                    if (body.contains("call_err_1") || body.contains("已被物理注销") || body.contains("ORD_ERROR")) {
                        return new MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "application/json; charset=utf-8")
                                .setBody("""
                                        {
                                          "id": "chatcmpl-err-final",
                                          "object": "chat.completion",
                                          "created": 1700000001,
                                          "model": "gpt-4o",
                                          "choices": [
                                            {
                                              "index": 0,
                                              "message": {
                                                "role": "assistant",
                                                "content": "```json\\n{\\n  \\"status\\": \\"REJECTED\\",\\n  \\"analysis\\": \\"订单在履约系统中不存在，审计驳回。\\",\\n  \\"riskScore\\": 95,\\n  \\"riskTags\\": [\\"订单不存在\\", \\"高风险拦截\\"]\\n}\\n```"
                                              },
                                              "finish_reason": "stop"
                                            }
                                          ]
                                        }
                                        """);
                    }

                    // 正常工具执行完成后的合规审计结果 (包含 Markdown ```json 脱壳验证)
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json; charset=utf-8")
                            .setBody("""
                                    {
                                      "id": "chatcmpl-audit-final",
                                      "object": "chat.completion",
                                      "created": 1700000002,
                                      "model": "gpt-4o",
                                      "choices": [
                                        {
                                          "index": 0,
                                          "message": {
                                            "role": "assistant",
                                            "content": "```json\\n{\\n  \\"status\\": \\"APPROVED\\",\\n  \\"analysis\\": \\"订单与库存履约核验完全一致，财务合规通过。\\",\\n  \\"riskScore\\": 12,\\n  \\"riskTags\\": [\\"正常支付\\", \\"现货库存充足\\"]\\n}\\n```"
                                          },
                                          "finish_reason": "stop"
                                        }
                                      ]
                                    }
                                    """);
                }

                // 3. 初始第一轮请求：根据订单号返回对应的 Tool Call
                if (body.contains("ORD_ERROR")) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json; charset=utf-8")
                            .setBody("""
                                    {
                                      "id": "chatcmpl-tool-err",
                                      "object": "chat.completion",
                                      "created": 1700000003,
                                      "model": "gpt-4o",
                                      "choices": [
                                        {
                                          "index": 0,
                                          "message": {
                                            "role": "assistant",
                                            "tool_calls": [
                                              {
                                                "id": "call_err_1",
                                                "type": "function",
                                                "function": {
                                                  "name": "queryOrder",
                                                  "arguments": "{\\"orderId\\":\\"ORD_ERROR\\"}"
                                                }
                                              }
                                            ]
                                          },
                                          "finish_reason": "tool_calls"
                                        }
                                      ]
                                    }
                                    """);
                }

                // 缺省/普通订单：返回标准 queryOrder Tool Call
                return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json; charset=utf-8")
                        .setBody("""
                                {
                                  "id": "chatcmpl-tool-normal",
                                  "object": "chat.completion",
                                  "created": 1700000004,
                                  "model": "gpt-4o",
                                  "choices": [
                                    {
                                      "index": 0,
                                      "message": {
                                        "role": "assistant",
                                        "tool_calls": [
                                          {
                                            "id": "call_normal_1",
                                            "type": "function",
                                            "function": {
                                              "name": "queryOrder",
                                              "arguments": "{\\"orderId\\":\\"ORD_20261103001\\"}"
                                            }
                                          }
                                        ]
                                      },
                                      "finish_reason": "tool_calls"
                                    }
                                  ]
                                }
                                """);
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
            registry.add("ateng.boot.ai.model.api-key", () -> "mock-test-key-ci");
            registry.add("ateng.boot.ai.model.model-name", () -> "gpt-4o");
        }
    }

    @Test
    @DisplayName("端到端验证 POST /sample/ai/agent/audit 智能体受管工具调度与财务决策通过")
    void testAuditOrderSuccess() throws Exception {
        FinancialAuditRequest request = FinancialAuditRequest.builder()
                .orderId("ORD_20261103001")
                .remarks("2026年度双十一财务大额订单专项清查")
                .build();

        var actions = mockMvc.perform(post("/sample/ai/agent/audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        if (!isLocalProfile()) {
            actions.andExpect(jsonPath("$.data.status").value("APPROVED"))
                    .andExpect(jsonPath("$.data.riskScore").value(12))
                    .andExpect(jsonPath("$.data.analysis").value("订单与库存履约核验完全一致，财务合规通过。"))
                    .andExpect(jsonPath("$.data.riskTags[0]").value("正常支付"));
        } else {
            actions.andExpect(jsonPath("$.data.status").isString())
                    .andExpect(jsonPath("$.data.riskScore").isNumber());
        }
    }

    @Test
    @DisplayName("端到端验证 POST /sample/ai/agent/audit 受管工具异常拦截与智能体自愈反思决策")
    void testAuditOrderToolExceptionSelfHealing() throws Exception {
        FinancialAuditRequest request = FinancialAuditRequest.builder()
                .orderId("ORD_ERROR")
                .remarks("拟核验已被注销的异常订单")
                .build();

        var actions = mockMvc.perform(post("/sample/ai/agent/audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        if (!isLocalProfile()) {
            actions.andExpect(jsonPath("$.data.status").value("REJECTED"))
                    .andExpect(jsonPath("$.data.riskScore").value(95))
                    .andExpect(jsonPath("$.data.analysis").value("订单在履约系统中不存在，审计驳回。"))
                    .andExpect(jsonPath("$.data.riskTags[0]").value("订单不存在"));
        } else {
            actions.andExpect(jsonPath("$.data.status").isString());
        }
    }

    @Test
    @DisplayName("端到端验证 POST /sample/ai/agent/audit/{sessionId} 跨轮多会话隔离审计与会话记忆存取")
    void testAuditOrderWithSessionMemory() throws Exception {
        String sessionId = "sess_audit_test_001";
        FinancialAuditRequest request = FinancialAuditRequest.builder()
                .orderId("ORD_20261103001")
                .remarks("会话轮次一：初次立案审计")
                .build();

        mockMvc.perform(post("/sample/ai/agent/audit/" + sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").isString());

        // 验证会话记忆管理器中成功记录了该 sessionId 的会话状态
        if (chatMemoryManager != null) {
            assertThat(chatMemoryManager.exists(sessionId)).isTrue();
            assertThat(chatMemoryManager.getMessages(sessionId)).isNotEmpty();
            // 清理测试会话现场，践行测试零污染铁律
            chatMemoryManager.clear(sessionId);
            assertThat(chatMemoryManager.exists(sessionId)).isFalse();
        }
    }

    @Test
    @DisplayName("端到端验证 POST /sample/ai/agent/audit 大模型输出评分越界触发 JSR-303 防卫熔断")
    void testAuditOrderJsr303DefenseViolation() throws Exception {
        if (isLocalProfile()) {
            // 本地真实模型无法精准诱导输出非法格式，离线 MockWebServer 专职覆盖
            return;
        }

        FinancialAuditRequest request = FinancialAuditRequest.builder()
                .orderId("ORD_VIOLATION_SCORE")
                .remarks("诱导大模型输出 150 风险评分越界")
                .build();

        mockMvc.perform(post("/sample/ai/agent/audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(61008));
    }

    @Test
    @DisplayName("端到端验证 POST /sample/ai/agent/audit 空订单号触发传输层 JSR-303 快速失败拦截")
    void testAuditOrderEmptyOrderIdFailFast() throws Exception {
        FinancialAuditRequest request = FinancialAuditRequest.builder()
                .orderId("   ")
                .remarks("空订单号测试")
                .build();

        mockMvc.perform(post("/sample/ai/agent/audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("端到端验证 GET /sample/ai/agent/audit/stream 智能体打字机流式推流审计")
    void testAuditOrderStreamSuccess() throws Exception {
        var mvcResult = mockMvc.perform(get("/sample/ai/agent/audit/stream")
                        .param("orderId", "ORD_STREAM_001")
                        .param("remarks", "实时流式合规核查")
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk());

        String sseOutput = mvcResult.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        if (!isLocalProfile()) {
            assertThat(sseOutput).contains("event:text");
            assertThat(sseOutput).contains("data:订单审计核验中...");
            assertThat(sseOutput).contains("data:财务合规通过。");
            assertThat(sseOutput).contains("event:done");
            assertThat(sseOutput).contains("data:[DONE]");
        } else {
            assertThat(sseOutput).contains("event:text");
            assertThat(sseOutput).contains("data:[DONE]");
        }
    }

    @Test
    @DisplayName("端到端验证 GET /sample/ai/agent/audit/stream 空订单号 JSR-303 快速失败拦截")
    void testAuditOrderStreamEmptyOrderIdFailFast() throws Exception {
        mockMvc.perform(get("/sample/ai/agent/audit/stream")
                        .param("orderId", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }
}
