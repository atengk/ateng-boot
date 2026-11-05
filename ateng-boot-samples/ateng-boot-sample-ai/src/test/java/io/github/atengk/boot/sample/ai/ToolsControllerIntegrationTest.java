package io.github.atengk.boot.sample.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.ai.mcp.McpClientAdapter;
import io.github.atengk.boot.ai.mcp.McpClientRegistry;
import io.github.atengk.boot.sample.ai.dto.AiDynamicToolRequest;
import io.github.atengk.boot.sample.ai.dto.AiMcpCallRequest;
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
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 编程式动态工具与 MCP 客户端端到端集成测试 (100% 离线自洽)
 *
 * @author Ateng
 * @since 2026-11-05
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("AI 编程式动态工具与 MCP 客户端端到端集成测试")
class ToolsControllerIntegrationTest {

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
    private McpClientRegistry mcpClientRegistry;

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
                          "id": "chatcmpl-tools-test-1",
                          "object": "chat.completion",
                          "created": 1700000000,
                          "model": "gpt-4o",
                          "choices": [
                            {
                              "index": 0,
                              "message": {
                                "role": "assistant",
                                "content": "计算结果为 1000。"
                              },
                              "finish_reason": "stop"
                            }
                          ],
                          "usage": {
                            "prompt_tokens": 30,
                            "completion_tokens": 10,
                            "total_tokens": 40
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
            registry.add("ateng.boot.ai.model.api-key", () -> "mock-test-key-tools-ci");
            registry.add("ateng.boot.ai.model.model-name", () -> "gpt-4o");
        }
    }

    @Test
    @DisplayName("验证 POST /sample/ai/tools/chat-dynamic 编程式动态工具对话")
    void testChatDynamicSuccess() throws Exception {
        AiDynamicToolRequest request = AiDynamicToolRequest.builder()
                .question("请帮我计算 125 * 8 是多少")
                .build();

        mockMvc.perform(post("/sample/ai/tools/chat-dynamic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.question").value(request.getQuestion()))
                .andExpect(jsonPath("$.data.answer").isNotEmpty())
                .andExpect(jsonPath("$.data.toolsUsed").isArray());
    }

    @Test
    @DisplayName("验证 GET /sample/ai/tools/mcp-list 获取 MCP 客户端清单")
    void testGetMcpListSuccess() throws Exception {
        mockMvc.perform(get("/sample/ai/tools/mcp-list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalServers").isNumber())
                .andExpect(jsonPath("$.data.servers").isArray());
    }

    @Test
    @DisplayName("验证 POST /sample/ai/tools/mcp-call 远程工具调用成功")
    void testCallMcpToolSuccess() throws Exception {
        if (mcpClientRegistry != null) {
            McpClientAdapter mockAdapter = org.mockito.Mockito.mock(McpClientAdapter.class);
            org.mockito.Mockito.when(mockAdapter.getServerName()).thenReturn("mock-server");
            org.mockito.Mockito.when(mockAdapter.callTool(org.mockito.ArgumentMatchers.eq("get_weather"), org.mockito.ArgumentMatchers.any()))
                    .thenReturn("晴朗 22°C");
            mcpClientRegistry.register("mock-server", mockAdapter);
        }

        AiMcpCallRequest request = AiMcpCallRequest.builder()
                .serverName("mock-server")
                .toolName("get_weather")
                .arguments(java.util.Map.of("city", "Beijing"))
                .build();

        mockMvc.perform(post("/sample/ai/tools/mcp-call")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.serverName").value("mock-server"))
                .andExpect(jsonPath("$.data.result").value("晴朗 22°C"));
    }

    @Test
    @DisplayName("验证 POST /sample/ai/tools/mcp-call 服务未找到时返回受控业务错误")
    void testCallMcpToolNotFound() throws Exception {
        AiMcpCallRequest request = AiMcpCallRequest.builder()
                .serverName("unknown-server-xyz")
                .toolName("test_tool")
                .build();

        mockMvc.perform(post("/sample/ai/tools/mcp-call")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(61019));
    }
}
