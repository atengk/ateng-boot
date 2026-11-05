package io.github.atengk.boot.ai.mcp;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 远程 MCP 客户端适配器 HttpMcpClientAdapter 与注册中心 McpClientRegistry 单元测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
class HttpMcpClientAdapterTest {

    private HttpClient httpClient;
    private HttpResponse<String> httpResponse;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        httpClient = mock(HttpClient.class);
        httpResponse = mock(HttpResponse.class);
    }

    @Test
    @DisplayName("测试 tools/list 成功发现远程 MCP 工具清单并转译为 ToolSpecification")
    @SuppressWarnings("unchecked")
    void testRefreshToolsSuccess() throws Exception {
        String listResponseJson = """
                {
                  "jsonrpc": "2.0",
                  "id": "list-123",
                  "result": {
                    "tools": [
                      {
                        "name": "get_weather",
                        "description": "查询城市当前天气",
                        "inputSchema": {
                          "type": "object",
                          "properties": {
                            "city": {
                              "type": "string",
                              "description": "城市名称"
                            },
                            "unit": {
                              "type": "string",
                              "description": "温度单位 c 或 f"
                            }
                          },
                          "required": ["city"]
                        }
                      }
                    ]
                  }
                }
                """;

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(listResponseJson);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        HttpMcpClientAdapter adapter = new HttpMcpClientAdapter(
                "weather-server",
                "http://localhost:8080/mcp",
                Duration.ofSeconds(10),
                "test-api-key",
                httpClient
        );

        Map<ToolSpecification, ToolExecutor> tools = adapter.getTools();
        assertThat(tools).hasSize(1);

        ToolSpecification spec = tools.keySet().iterator().next();
        assertThat(spec.name()).isEqualTo("get_weather");
        assertThat(spec.description()).isEqualTo("查询城市当前天气");
        assertThat(spec.parameters()).isInstanceOf(JsonObjectSchema.class);
        assertThat(spec.parameters().properties()).containsKeys("city", "unit");
        assertThat(spec.parameters().required()).contains("city");

        // 验证请求头附带 Bearer token
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any());
        HttpRequest request = requestCaptor.getValue();
        assertThat(request.headers().firstValue("Authorization")).contains("Bearer test-api-key");
    }

    @Test
    @DisplayName("测试 tools/call 正常调用远程 MCP 工具并返回内容")
    @SuppressWarnings("unchecked")
    void testCallToolSuccess() throws Exception {
        String callResponseJson = """
                {
                  "jsonrpc": "2.0",
                  "id": "call-456",
                  "result": {
                    "content": [
                      {
                        "type": "text",
                        "text": "北京今日晴朗，气温 25°C"
                      }
                    ]
                  }
                }
                """;

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(callResponseJson);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        HttpMcpClientAdapter adapter = new HttpMcpClientAdapter(
                "weather-server",
                "http://localhost:8080/mcp",
                Duration.ofSeconds(10),
                null,
                httpClient
        );

        String result = adapter.callTool("get_weather", Map.of("city", "Beijing"));
        assertThat(result).isEqualTo("北京今日晴朗，气温 25°C");
    }

    @Test
    @DisplayName("测试 tools/call 返回 isError=true 时抛出 BusinessException")
    @SuppressWarnings("unchecked")
    void testCallToolIsError() throws Exception {
        String errorResponseJson = """
                {
                  "jsonrpc": "2.0",
                  "id": "call-error",
                  "result": {
                    "isError": true,
                    "content": "未查询到指定城市 [Atlantis] 的气象数据"
                  }
                }
                """;

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(errorResponseJson);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        HttpMcpClientAdapter adapter = new HttpMcpClientAdapter(
                "weather-server",
                "http://localhost:8080/mcp",
                Duration.ofSeconds(10),
                null,
                httpClient
        );

        assertThatThrownBy(() -> adapter.callTool("get_weather", Map.of("city", "Atlantis")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getResultCode()).isEqualTo(AiResultCode.AI_MCP_CALL_FAILED))
                .hasMessageContaining("未查询到指定城市 [Atlantis] 的气象数据");
    }

    @Test
    @DisplayName("测试 JSON-RPC 顶层 error 响应抛出 BusinessException")
    @SuppressWarnings("unchecked")
    void testCallToolJsonRpcError() throws Exception {
        String rpcErrorJson = """
                {
                  "jsonrpc": "2.0",
                  "id": "call-err",
                  "error": {
                    "code": -32601,
                    "message": "Method not found"
                  }
                }
                """;

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(rpcErrorJson);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        HttpMcpClientAdapter adapter = new HttpMcpClientAdapter(
                "weather-server",
                "http://localhost:8080/mcp",
                Duration.ofSeconds(10),
                null,
                httpClient
        );

        assertThatThrownBy(() -> adapter.callTool("unknown_tool", Collections.emptyMap()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getResultCode()).isEqualTo(AiResultCode.AI_MCP_CALL_FAILED))
                .hasMessageContaining("Method not found");
    }

    @Test
    @DisplayName("测试 HTTP 500 异常时受控包装为 BusinessException")
    @SuppressWarnings("unchecked")
    void testHttpServerError() throws Exception {
        when(httpResponse.statusCode()).thenReturn(500);
        when(httpResponse.body()).thenReturn("Internal Server Error");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        HttpMcpClientAdapter adapter = new HttpMcpClientAdapter(
                "weather-server",
                "http://localhost:8080/mcp",
                Duration.ofSeconds(10),
                null,
                httpClient
        );

        assertThatThrownBy(() -> adapter.callTool("get_weather", Collections.emptyMap()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getResultCode()).isEqualTo(AiResultCode.AI_MCP_CALL_FAILED))
                .hasMessageContaining("HTTP 500");
    }

    @Test
    @DisplayName("测试 SSE 传输端点握手并解析实际消息 POST 端点")
    @SuppressWarnings("unchecked")
    void testSseTransportHandshakeAndToolCall() throws Exception {
        // 1. Mock SSE GET 握手返回 endpoint 事件
        String sseStreamData = "event: endpoint\ndata: /messages?sessionId=session_xyz123\n\n";
        HttpResponse<java.io.InputStream> sseResponse = mock(HttpResponse.class);
        when(sseResponse.statusCode()).thenReturn(200);
        when(sseResponse.body()).thenReturn(new java.io.ByteArrayInputStream(sseStreamData.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        // 2. Mock 随后的 POST tools/call 响应
        HttpResponse<String> postResponse = mock(HttpResponse.class);
        when(postResponse.statusCode()).thenReturn(200);
        when(postResponse.body()).thenReturn("""
                {
                  "jsonrpc": "2.0",
                  "id": "call-sse-1",
                  "result": {
                    "content": "SSE 传输调用成功"
                  }
                }
                """);

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest req = invocation.getArgument(0);
                    if ("GET".equalsIgnoreCase(req.method())) {
                        return sseResponse;
                    } else {
                        return postResponse;
                    }
                });

        HttpMcpClientAdapter adapter = new HttpMcpClientAdapter(
                "sse-server",
                "http://localhost:8080/sse",
                Duration.ofSeconds(10),
                "token-abc",
                "sse",
                httpClient
        );

        assertThat(adapter.isSseEndpoint()).isTrue();
        String effectiveUrl = adapter.getEffectiveEndpointUrl();
        assertThat(effectiveUrl).isEqualTo("http://localhost:8080/messages?sessionId=session_xyz123");

        String result = adapter.callTool("echo", Map.of("msg", "hello"));
        assertThat(result).isEqualTo("SSE 传输调用成功");
    }

    @Test
    @DisplayName("测试 McpClientRegistry 注册、注销、聚合工具功能")
    void testMcpClientRegistry() {
        McpClientRegistry registry = new McpClientRegistry();
        assertThat(registry.isEmpty()).isTrue();
        assertThat(registry.size()).isZero();

        McpClientAdapter adapter1 = mock(McpClientAdapter.class);
        when(adapter1.getServerName()).thenReturn("server1");
        ToolSpecification spec1 = ToolSpecification.builder().name("tool1").build();
        ToolExecutor executor1 = (req, mem) -> "res1";
        when(adapter1.getTools()).thenReturn(Map.of(spec1, executor1));

        registry.register("server1", adapter1);
        assertThat(registry.contains("server1")).isTrue();
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.getAdapter("server1")).isSameAs(adapter1);

        Map<ToolSpecification, ToolExecutor> allTools = registry.getAllTools();
        assertThat(allTools).hasSize(1);
        assertThat(allTools).containsKey(spec1);

        McpClientAdapter removed = registry.unregister("server1");
        assertThat(removed).isSameAs(adapter1);
        assertThat(registry.isEmpty()).isTrue();
    }
}
