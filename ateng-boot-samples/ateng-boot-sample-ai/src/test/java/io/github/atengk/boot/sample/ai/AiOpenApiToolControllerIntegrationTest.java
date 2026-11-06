package io.github.atengk.boot.sample.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.ai.controller.AiOpenApiToolController;
import io.github.atengk.boot.sample.ai.dto.OpenApiChatRequest;
import io.github.atengk.boot.sample.ai.dto.OpenApiExecuteRequest;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
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
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI 契约转受管沙箱工具控制器端到端集成测试 (100% 离线自洽)
 *
 * @author Ateng
 * @since 2026-11-06
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("OpenAPI 契约转受管沙箱工具控制器端到端集成测试")
class AiOpenApiToolControllerIntegrationTest {

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

    @Autowired
    private AiOpenApiToolController aiOpenApiToolController;

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

                // 1. 模拟 OpenAI ChatCompletion 接口响应
                if (path.startsWith("/v1/chat/completions")) {
                    String chatResponse = """
                            {
                              "id": "chatcmpl-openapi-test-1",
                              "object": "chat.completion",
                              "created": 1700000000,
                              "model": "gpt-4o",
                              "choices": [
                                {
                                  "index": 0,
                                  "message": {
                                    "role": "assistant",
                                    "content": "查询到订单详情：订单号 1001，买家张三，金额 299 元，状态已支付。"
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
                            .setBody(chatResponse);
                }

                // 2. 模拟 OpenAPI 目标订单服务接口响应
                if (path.contains("/api/v1/orders/1001")) {
                    String orderJson = """
                            {
                              "id": 1001,
                              "buyerName": "张三",
                              "amount": 299.0,
                              "status": "PAID",
                              "channel": "ONLINE"
                            }
                            """;
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json; charset=utf-8")
                            .setBody(orderJson);
                }

                if (path.contains("/api/v1/orders") && "POST".equalsIgnoreCase(request.getMethod())) {
                    String createdJson = """
                            {
                              "id": 1002,
                              "buyerName": "李四",
                              "amount": 199.5,
                              "status": "CREATED"
                            }
                            """;
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json; charset=utf-8")
                            .setBody(createdJson);
                }

                if (path.contains("/api/v1/orders")) {
                    String listJson = """
                            [
                              {
                                "id": 1001,
                                "buyerName": "张三",
                                "amount": 299.0,
                                "status": "PAID"
                              }
                            ]
                            """;
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json; charset=utf-8")
                            .setBody(listJson);
                }

                return new MockResponse().setResponseCode(404).setBody("{\"error\": \"Not Found\"}");
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
            registry.add("ateng.boot.ai.model.api-key", () -> "mock-test-key-openapi-ci");
            registry.add("ateng.boot.ai.model.model-name", () -> "gpt-4o");
        }
    }

    @BeforeEach
    void setUp() {
        if (!isLocalProfile() && server != null) {
            // 将 OpenAPI 适配器的 baseUrl 指向 MockWebServer 地址
            aiOpenApiToolController.initAdapter("openapi/order-service-api.json", server.url("").toString());
        }
    }

    @Test
    @DisplayName("验证 GET /sample/ai/agent/openapi-tools/specs 获取工具规格列表")
    void testGetSpecsSuccess() throws Exception {
        mockMvc.perform(get("/sample/ai/agent/openapi-tools/specs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[*].operationId", hasItem("getOrderById")))
                .andExpect(jsonPath("$.data[*].operationId", hasItem("createOrder")))
                .andExpect(jsonPath("$.data[*].operationId", hasItem("listOrders")))
                .andExpect(jsonPath("$.data[*].operationId", hasItem("cancelOrder")));
    }

    @Test
    @DisplayName("验证 POST /sample/ai/agent/openapi-tools/execute 直接调用目标工具")
    void testExecuteToolSuccess() throws Exception {
        OpenApiExecuteRequest request = new OpenApiExecuteRequest();
        request.setOperationId("getOrderById");
        request.setArguments(Map.of("id", 1001, "includeItems", true));

        mockMvc.perform(post("/sample/ai/agent/openapi-tools/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", containsString("张三")))
                .andExpect(jsonPath("$.data", containsString("PAID")));
    }

    @Test
    @DisplayName("验证 POST /sample/ai/agent/openapi-tools/chat 挂载工具智能对话")
    void testChatWithOpenApiToolsSuccess() throws Exception {
        OpenApiChatRequest request = new OpenApiChatRequest();
        request.setPrompt("请帮我查询订单 1001 的当前状态与买家姓名");

        mockMvc.perform(post("/sample/ai/agent/openapi-tools/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.response", containsString("订单")))
                .andExpect(jsonPath("$.data.toolsAvailable", hasItem("getOrderById")));
    }

    @Test
    @DisplayName("验证 POST /sample/ai/agent/openapi-tools/reload 动态重载契约工具")
    void testReloadToolsSuccess() throws Exception {
        mockMvc.perform(post("/sample/ai/agent/openapi-tools/reload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", containsString("成功")));
    }
}
