package io.github.atengk.boot.ai.mcp;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.JsonUtil;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
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

        // 验证请求头附带 Bearer token (initialize + notifications/initialized + tools/list)
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, atLeastOnce()).send(requestCaptor.capture(), any());
        HttpRequest request = requestCaptor.getAllValues().get(0);
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

    @Test
    @DisplayName("MockWebServer：Streamable HTTP 自动完成 initialize 握手、透传 Mcp-Session-Id、解析 SSE 响应及资源/提示词契约")
    @SuppressWarnings("unchecked")
    void testStreamableHttpFullLifecycleAndSymmetricContracts() throws Exception {
        java.util.List<RecordedRequest> captured = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (MockWebServer mockWebServer = new MockWebServer()) {
            mockWebServer.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    captured.add(request);
                    String body = request.getBody().readUtf8();
                    Map<String, Object> payload = JsonUtil.parseObject(body, Map.class);
                    String method = String.valueOf(payload.get("method"));
                    Object id = payload.get("id");

                    return switch (method) {
                        case "initialize" -> new MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "application/json")
                                .setHeader("Mcp-Session-Id", "sess-streamable-99")
                                .setBody("{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"serverInfo\":{\"name\":\"demo\",\"version\":\"1.0\"}}}");
                        case "notifications/initialized" -> {
                            assertThat(request.getHeader("Mcp-Session-Id")).isEqualTo("sess-streamable-99");
                            yield new MockResponse().setResponseCode(202);
                        }
                        case "tools/list" -> {
                            assertThat(request.getHeader("Mcp-Session-Id")).isEqualTo("sess-streamable-99");
                            yield new MockResponse()
                                    .setResponseCode(200)
                                    .setHeader("Content-Type", "text/event-stream; charset=utf-8")
                                    .setBody("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"tools\":[{\"name\":\"ping\",\"description\":\"pong\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}]}}\n\n");
                        }
                        case "resources/list" -> {
                            assertThat(request.getHeader("Mcp-Session-Id")).isEqualTo("sess-streamable-99");
                            yield new MockResponse()
                                    .setResponseCode(200)
                                    .setHeader("Content-Type", "application/json")
                                    .setBody("{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"resources\":[{\"uri\":\"file:///README.md\",\"name\":\"readme\",\"description\":\"项目说明\",\"mimeType\":\"text/markdown\"}]}}");
                        }
                        case "resources/read" -> new MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "application/json")
                                .setBody("{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"contents\":[{\"uri\":\"file:///README.md\",\"text\":\"# Hello MCP Resource\"}]}}");
                        case "prompts/list" -> new MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "application/json")
                                .setBody("{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"prompts\":[{\"name\":\"code_review\",\"description\":\"代码评审模板\",\"arguments\":[{\"name\":\"lang\",\"description\":\"语言\",\"required\":true}]}]}}");
                        case "prompts/get" -> new MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "application/json")
                                .setBody("{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"messages\":[{\"role\":\"user\",\"content\":{\"type\":\"text\",\"text\":\"Review Java code\"}}]}}");
                        default -> new MockResponse().setResponseCode(400);
                    };
                }
            });
            mockWebServer.start();

            try (HttpMcpClientAdapter adapter = new HttpMcpClientAdapter(
                    "streamable-mcp", mockWebServer.url("/mcp").toString(), Duration.ofSeconds(5), "secret-k", "http", false)) {
                assertThat(adapter.getTools()).hasSize(1);
                java.util.List<McpResource> resources = adapter.listResources();
                assertThat(resources).hasSize(1);
                assertThat(resources.get(0).uri()).isEqualTo("file:///README.md");
                assertThat(adapter.readResource("file:///README.md")).contains("Hello MCP Resource");

                java.util.List<McpPrompt> prompts = adapter.listPrompts();
                assertThat(prompts).hasSize(1);
                assertThat(prompts.get(0).name()).isEqualTo("code_review");
                assertThat(adapter.getPrompt("code_review", Map.of("lang", "Java"))).contains("Review Java code");
            }
        }
    }

    @Test
    @DisplayName("持久 Legacy SSE：不关闭 GET 长连接前提下异步关联 JSON-RPC 响应并确定性 close")
    @SuppressWarnings("unchecked")
    void testPersistentLegacySseBidirectionalCorrelationAndClose() throws Exception {
        java.util.concurrent.atomic.AtomicInteger sseGetCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.BlockingQueue<String> pendingSsePayloads = new java.util.concurrent.LinkedBlockingQueue<>();
        java.util.concurrent.CountDownLatch sseClosedLatch = new java.util.concurrent.CountDownLatch(1);

        com.sun.net.httpserver.HttpServer sseServer = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        java.util.concurrent.ExecutorService serverExecutor = new java.util.concurrent.ThreadPoolExecutor(
                2,
                16,
                60L,
                java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.SynchronousQueue<>(),
                new io.github.atengk.boot.core.concurrent.NamedThreadFactory("test-mcp-sse", true)
        );
        sseServer.setExecutor(serverExecutor);
        sseServer.createContext("/sse", exchange -> {
            sseGetCount.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write("event: endpoint\ndata: /messages?sessionId=legacy-1\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                os.flush();
                while (!Thread.currentThread().isInterrupted()) {
                    String frame = pendingSsePayloads.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if ("__CLOSE__".equals(frame)) {
                        break;
                    }
                    String toWrite = frame != null ? frame : ": ping\n\n";
                    os.write(toWrite.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    os.flush();
                }
            } catch (Exception ignored) {
            } finally {
                sseClosedLatch.countDown();
            }
        });
        sseServer.createContext("/messages", exchange -> {
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            Map<String, Object> payload = JsonUtil.parseObject(reqBody, Map.class);
            String method = String.valueOf(payload.get("method"));
            Object id = payload.get("id");

            if (!"notifications/initialized".equals(method)) {
                String resultJson = switch (method) {
                    case "initialize" -> "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{}}";
                    case "tools/list" -> "{\"tools\":[{\"name\":\"echo_sse\",\"description\":\"sse\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}]}";
                    case "tools/call" -> "{\"content\":[{\"type\":\"text\",\"text\":\"legacy-sse-ok\"}]}";
                    case "resources/list" -> "{\"resources\":[{\"uri\":\"mem://res1\",\"name\":\"r1\",\"mimeType\":\"text/plain\"}]}";
                    case "resources/read" -> "{\"contents\":[{\"uri\":\"mem://res1\",\"text\":\"res-content\"}]}";
                    case "prompts/list" -> "{\"prompts\":[{\"name\":\"p1\",\"description\":\"desc\"}]}";
                    case "prompts/get" -> "{\"messages\":[{\"role\":\"user\",\"content\":{\"type\":\"text\",\"text\":\"prompt-text\"}}]}";
                    default -> "{}";
                };
                pendingSsePayloads.offer("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":" + resultJson + "}\n\n");
            }
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        sseServer.start();
        HttpMcpClientAdapter closedAdapterRef;
        try {
            String sseUrl = "http://127.0.0.1:" + sseServer.getAddress().getPort() + "/sse";
            try (HttpMcpClientAdapter adapter = new HttpMcpClientAdapter(
                    "legacy-sse", sseUrl, Duration.ofSeconds(5), null, "sse", false)) {
                closedAdapterRef = adapter;
                assertThat(adapter.getTools()).hasSize(1);
                assertThat(adapter.callTool("echo_sse", Collections.emptyMap())).isEqualTo("legacy-sse-ok");
                assertThat(adapter.listResources()).hasSize(1);
                assertThat(adapter.readResource("mem://res1")).isEqualTo("res-content");
                assertThat(adapter.listPrompts()).hasSize(1);
                assertThat(adapter.getPrompt("p1", Collections.emptyMap())).contains("prompt-text");
                assertThat(sseGetCount.get()).isEqualTo(1);
            }
            assertThat(sseClosedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> closedAdapterRef.callTool("echo_sse", Collections.emptyMap()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getResultCode()).isEqualTo(AiResultCode.AI_MCP_CALL_FAILED));
        } finally {
            pendingSsePayloads.offer("__CLOSE__");
            sseServer.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    @Test
    @DisplayName("持久 Legacy SSE：首次握手后断连，下次调用自动重建 SSE 长连接并重新 initialize")
    @SuppressWarnings("unchecked")
    void testPersistentLegacySseReconnectTriggersInitialize() throws Exception {
        java.util.concurrent.atomic.AtomicInteger sseConnections = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger initCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.BlockingQueue<String> queue1 = new java.util.concurrent.LinkedBlockingQueue<>();
        java.util.concurrent.BlockingQueue<String> queue2 = new java.util.concurrent.LinkedBlockingQueue<>();
        java.util.concurrent.CountDownLatch firstSseEnded = new java.util.concurrent.CountDownLatch(1);

        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        java.util.concurrent.ExecutorService serverExec = new java.util.concurrent.ThreadPoolExecutor(
                2, 16, 60L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.SynchronousQueue<>(),
                new io.github.atengk.boot.core.concurrent.NamedThreadFactory("test-sse-reconnect", true));
        server.setExecutor(serverExec);
        server.createContext("/sse", exchange -> {
            int conn = sseConnections.incrementAndGet();
            java.util.concurrent.BlockingQueue<String> q = (conn == 1) ? queue1 : queue2;
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write("event: endpoint\ndata: /messages\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                os.flush();
                while (!Thread.currentThread().isInterrupted()) {
                    String frame = q.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if ("__DROP__".equals(frame)) {
                        break;
                    }
                    if (frame != null) {
                        os.write(frame.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        os.flush();
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (conn == 1) {
                    firstSseEnded.countDown();
                }
            }
        });
        server.createContext("/messages", exchange -> {
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            Map<String, Object> payload = JsonUtil.parseObject(reqBody, Map.class);
            String method = String.valueOf(payload.get("method"));
            Object id = payload.get("id");
            java.util.concurrent.BlockingQueue<String> targetQueue = sseConnections.get() == 1 ? queue1 : queue2;
            if ("initialize".equals(method)) {
                initCalls.incrementAndGet();
                targetQueue.offer("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"protocolVersion\":\"2024-11-05\"}}\n\n");
            } else if ("tools/call".equals(method)) {
                targetQueue.offer("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok-" + sseConnections.get() + "\"}]}}\n\n");
            }
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/sse";
            try (HttpMcpClientAdapter adapter = new HttpMcpClientAdapter(
                    "reconnect-sse", url, Duration.ofSeconds(5), null, "sse", false)) {
                assertThat(adapter.callTool("ping", Collections.emptyMap())).isEqualTo("ok-1");
                assertThat(initCalls.get()).isEqualTo(1);

                queue1.offer("__DROP__");
                assertThat(firstSseEnded.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                // 短暂等待客户端 SSE 读取线程完成断开状态收敛
                long deadline = System.currentTimeMillis() + 2000;
                while (adapter.isSseEndpoint() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20);
                    if (initCalls.get() >= 1) {
                        break;
                    }
                }
                Thread.sleep(100);

                assertThat(adapter.callTool("ping", Collections.emptyMap())).isEqualTo("ok-2");
                assertThat(sseConnections.get()).isEqualTo(2);
                assertThat(initCalls.get()).isEqualTo(2);
            }
        } finally {
            queue1.offer("__DROP__");
            queue2.offer("__DROP__");
            server.stop(0);
            serverExec.shutdownNow();
        }
    }
}
