package io.github.atengk.boot.ai.mcp;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.AsyncUtil;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于 HTTP JSON-RPC 2.0 的远程 MCP 客户端适配器实现。
 * <p>
 * 纯原生 JDK 17 {@link HttpClient} 实现，默认复用受管共享实例与 Core 命名线程池：
 * <ul>
 *   <li>完整 MCP 会话握手：首次请求或重连后自动执行 {@code initialize} 与 {@code notifications/initialized}；</li>
 *   <li>Streamable HTTP：自动捕获并透传 {@code Mcp-Session-Id} 标头，兼容直接 JSON 与 POST SSE 事件流响应；</li>
 *   <li>持久 Legacy SSE：后台维护 GET 长连接监听流，依据 {@code id} 异步关联 JSON-RPC 响应并支持断线重连；</li>
 *   <li>对称扩展支持工具、资源（{@code resources/*}）与提示词模板（{@code prompts/*}），并在 {@link #close()} 时确定性释放。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class HttpMcpClientAdapter implements McpClientAdapter, AutoCloseable, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(HttpMcpClientAdapter.class);

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final String MCP_PROTOCOL_VERSION = "2024-11-05";
    private static final String HEADER_MCP_SESSION_ID = "Mcp-Session-Id";

    private static final HttpClient SHARED_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
            .executor(task -> AsyncUtil.getExecutor().execute(AsyncContext.wrap(task)))
            .build();

    private final String serverName;
    private final String endpointUrl;
    private final Duration timeout;
    private final String apiKey;
    private final String transport;
    private final HttpClient httpClient;
    private final Map<ToolSpecification, ToolExecutor> toolsMap = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<String>> pendingResponses = new ConcurrentHashMap<>();
    private final ReentrantLock toolsLock = new ReentrantLock();
    private final ReentrantLock sessionLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean sseConnected = new AtomicBoolean(false);

    private volatile String resolvedPostUrl;
    private volatile String mcpSessionId;
    private volatile boolean initialized;
    private volatile boolean sseStreamNeedsReconnect;
    private volatile InputStream activeSseInputStream;
    private volatile Future<?> sseReaderFuture;

    public HttpMcpClientAdapter(String serverName, String endpointUrl) {
        this(serverName, endpointUrl, Duration.ofSeconds(30), null, true);
    }

    public HttpMcpClientAdapter(String serverName, String endpointUrl, Duration timeout, String apiKey, boolean eagerInit) {
        this(serverName, endpointUrl, timeout, apiKey, null, eagerInit);
    }

    public HttpMcpClientAdapter(String serverName, String endpointUrl, Duration timeout, String apiKey, String transport, boolean eagerInit) {
        this(serverName, endpointUrl, timeout, apiKey, transport, SHARED_HTTP_CLIENT);
        if (eagerInit) {
            try {
                refreshTools();
            } catch (Exception e) {
                log.warn("[AI-MCP] 预热初始化远程 MCP 服务 [{}] 发现工具失败 ({})，将在首次使用时重试",
                        serverName, e.getMessage());
            }
        }
    }

    public HttpMcpClientAdapter(String serverName, String endpointUrl, Duration timeout, String apiKey, HttpClient httpClient) {
        this(serverName, endpointUrl, timeout, apiKey, null, httpClient);
    }

    public HttpMcpClientAdapter(String serverName, String endpointUrl, Duration timeout, String apiKey, String transport, HttpClient httpClient) {
        Assert.hasText(serverName, "serverName 不能为空");
        Assert.hasText(endpointUrl, "endpointUrl 不能为空");
        this.serverName = serverName.trim();
        this.endpointUrl = endpointUrl.trim();
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(30);
        this.apiKey = apiKey;
        this.transport = transport != null ? transport.trim() : null;
        this.httpClient = httpClient != null ? httpClient : SHARED_HTTP_CLIENT;
    }

    @Override
    public Map<ToolSpecification, ToolExecutor> getTools() {
        ensureNotClosed();
        if (toolsMap.isEmpty()) {
            toolsLock.lock();
            try {
                if (toolsMap.isEmpty()) {
                    refreshTools();
                }
            } finally {
                if (toolsLock.isHeldByCurrentThread()) {
                    toolsLock.unlock();
                }
            }
        }
        return Collections.unmodifiableMap(toolsMap);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void refreshTools() {
        ensureNotClosed();
        log.info("[AI-MCP] 正在向远程 MCP 服务 [{}] 发起工具清单发现请求 (tools/list)...", serverName);

        Map<String, Object> result = invokeRpc("list-" + IdUtil.fastSimpleUUID(), "tools/list", Collections.emptyMap());
        if (result == null || !result.containsKey("tools")) {
            log.warn("[AI-MCP] 远程 MCP 服务 [{}] result 中未包含 tools 节点", serverName);
            return;
        }

        List<?> rawTools = (List<?>) result.get("tools");
        if (rawTools == null || rawTools.isEmpty()) {
            log.info("[AI-MCP] 远程 MCP 服务 [{}] 暴露的工具列表为空", serverName);
            toolsMap.clear();
            return;
        }

        Map<ToolSpecification, ToolExecutor> newTools = new LinkedHashMap<>();
        for (Object item : rawTools) {
            if (!(item instanceof Map<?, ?> toolMap)) {
                continue;
            }
            String toolName = asString(toolMap.get("name"));
            String description = asString(toolMap.get("description"));
            Map<String, Object> inputSchema = (Map<String, Object>) toolMap.get("inputSchema");

            if (!StringUtils.hasText(toolName)) {
                continue;
            }

            ToolSpecification specification = ToolSpecification.builder()
                    .name(toolName)
                    .description(description != null ? description : "")
                    .parameters(McpSchemaConverter.convert(inputSchema))
                    .build();

            ToolExecutor executor = (request, memoryId) -> {
                Map<String, Object> args = Collections.emptyMap();
                if (request != null && StringUtils.hasText(request.arguments())) {
                    Map<String, Object> parsed = JsonUtil.parseObject(request.arguments(), Map.class);
                    if (parsed != null) {
                        args = parsed;
                    }
                }
                return callTool(toolName, args);
            };

            newTools.put(specification, executor);
        }

        toolsMap.clear();
        toolsMap.putAll(newTools);
        log.info("[AI-MCP] 远程 MCP 服务 [{}] 工具清单发现完成，共加载 {} 项可执行工具: {}",
                serverName, newTools.size(), newTools.keySet().stream().map(ToolSpecification::name).toList());
    }

    @Override
    public String callTool(String toolName, Map<String, Object> arguments) {
        ensureNotClosed();
        Assert.hasText(toolName, "toolName 不能为空");

        log.debug("[AI-MCP] 正在调用远程 MCP 工具 [server={}, tool={}, args={}]", serverName, toolName, arguments);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName.trim());
        params.put("arguments", arguments != null ? arguments : Collections.emptyMap());

        Map<String, Object> result = invokeRpc("call-" + IdUtil.fastSimpleUUID(), "tools/call", params);
        if (result == null) {
            return "Success";
        }

        if (Boolean.TRUE.equals(result.get("isError"))) {
            String errContent = extractTextContent(result.get("content"));
            throw new BusinessException(AiResultCode.AI_MCP_CALL_FAILED,
                    String.format("MCP 工具 [%s] 执行失败: %s", toolName, errContent));
        }

        String contentText = extractTextContent(result.get("content"));
        return StringUtils.hasText(contentText) ? contentText : "Success";
    }

    @Override
    public List<McpResource> listResources() {
        ensureNotClosed();
        Map<String, Object> result = invokeRpc("res-list-" + IdUtil.fastSimpleUUID(), "resources/list", Collections.emptyMap());
        if (result == null || !(result.get("resources") instanceof List<?> rawResources) || rawResources.isEmpty()) {
            return Collections.emptyList();
        }
        List<McpResource> resources = new ArrayList<>();
        for (Object item : rawResources) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String uri = asString(map.get("uri"));
            if (!StringUtils.hasText(uri)) {
                continue;
            }
            resources.add(new McpResource(
                    uri,
                    asString(map.get("name")),
                    asString(map.get("description")),
                    asString(map.get("mimeType"))
            ));
        }
        return Collections.unmodifiableList(resources);
    }

    @Override
    public String readResource(String uri) {
        ensureNotClosed();
        Assert.hasText(uri, "uri 不能为空");
        Map<String, Object> result = invokeRpc("res-read-" + IdUtil.fastSimpleUUID(),
                "resources/read", Collections.singletonMap("uri", uri.trim()));
        if (result == null || !(result.get("contents") instanceof List<?> contents) || contents.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object item : contents) {
            if (item instanceof Map<?, ?> map) {
                Object text = map.get("text");
                if (text != null) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(text);
                } else if (map.get("blob") != null) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(map.get("blob"));
                }
            }
        }
        return sb.toString();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<McpPrompt> listPrompts() {
        ensureNotClosed();
        Map<String, Object> result = invokeRpc("prompt-list-" + IdUtil.fastSimpleUUID(), "prompts/list", Collections.emptyMap());
        if (result == null || !(result.get("prompts") instanceof List<?> rawPrompts) || rawPrompts.isEmpty()) {
            return Collections.emptyList();
        }
        List<McpPrompt> prompts = new ArrayList<>();
        for (Object item : rawPrompts) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String name = asString(map.get("name"));
            if (!StringUtils.hasText(name)) {
                continue;
            }
            List<McpPrompt.McpPromptArgument> args = new ArrayList<>();
            if (map.get("arguments") instanceof List<?> rawArgs) {
                for (Object argObj : rawArgs) {
                    if (argObj instanceof Map<?, ?> argMap) {
                        String argName = asString(argMap.get("name"));
                        if (StringUtils.hasText(argName)) {
                            args.add(new McpPrompt.McpPromptArgument(
                                    argName,
                                    asString(argMap.get("description")),
                                    Boolean.TRUE.equals(argMap.get("required"))
                            ));
                        }
                    }
                }
            }
            prompts.add(new McpPrompt(name, asString(map.get("description")), args));
        }
        return Collections.unmodifiableList(prompts);
    }

    @Override
    public String getPrompt(String name, Map<String, Object> arguments) {
        ensureNotClosed();
        Assert.hasText(name, "prompt name 不能为空");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name.trim());
        params.put("arguments", arguments != null ? arguments : Collections.emptyMap());

        Map<String, Object> result = invokeRpc("prompt-get-" + IdUtil.fastSimpleUUID(), "prompts/get", params);
        if (result == null || !(result.get("messages") instanceof List<?> messages) || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object msgObj : messages) {
            if (!(msgObj instanceof Map<?, ?> msgMap)) {
                continue;
            }
            Object content = msgMap.get("content");
            String text = extractPromptMessageText(content);
            if (StringUtils.hasText(text)) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(text);
            }
        }
        return sb.toString();
    }

    private String extractPromptMessageText(Object content) {
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof Map<?, ?> map) {
            Object text = map.get("text");
            return text != null ? String.valueOf(text) : JsonUtil.toJsonString(map);
        }
        return content != null ? extractTextContent(content) : "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeRpc(String requestId, String method, Map<String, Object> params) {
        ensureInitialized();
        Map<String, Object> rpcRequest = new LinkedHashMap<>();
        rpcRequest.put("jsonrpc", "2.0");
        rpcRequest.put("id", requestId);
        rpcRequest.put("method", method);
        rpcRequest.put("params", params != null ? params : Collections.emptyMap());

        String responseBody = sendRawJsonRpc(rpcRequest, false);
        return parseAndValidateRpcResult(responseBody, method);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseAndValidateRpcResult(String responseBody, String method) {
        if (!StringUtils.hasText(responseBody)) {
            return Collections.emptyMap();
        }
        Map<String, Object> parsedResponse = JsonUtil.parseObject(responseBody, Map.class);
        if (parsedResponse == null) {
            throw new BusinessException(AiResultCode.AI_MCP_CALL_FAILED,
                    String.format("远程 MCP 服务 [%s] 调用 [%s] 返回非法响应体", serverName, method));
        }
        if (parsedResponse.containsKey("error")) {
            Map<String, Object> error = (Map<String, Object>) parsedResponse.get("error");
            String errorMsg = error != null ? String.valueOf(error.get("message")) : "未知错误";
            throw new BusinessException(AiResultCode.AI_MCP_CALL_FAILED,
                    String.format("远程 MCP 服务 [%s] 执行 [%s] 报错: %s", serverName, method, errorMsg));
        }
        return (Map<String, Object>) parsedResponse.get("result");
    }

    private void ensureInitialized() {
        if (initialized && (!isSseEndpoint() || sseConnected.get() || !sseStreamNeedsReconnect)) {
            return;
        }
        sessionLock.lock();
        try {
            ensureNotClosed();
            if (initialized && (!isSseEndpoint() || sseConnected.get() || !sseStreamNeedsReconnect)) {
                return;
            }
            // SSE 持久流断开后触发重置投递地址与重连握手
            if (isSseEndpoint() && !sseConnected.get() && sseStreamNeedsReconnect) {
                resolvedPostUrl = null;
                sseStreamNeedsReconnect = false;
                initialized = false;
            }

            // 1. 触发 endpoint 获取（SSE 模式下拉起持久 GET 监听流）
            getEffectiveEndpointUrl();

            // 2. 执行标准 MCP initialize 握手
            Map<String, Object> clientInfo = new LinkedHashMap<>();
            clientInfo.put("name", "ateng-boot-ai");
            clientInfo.put("version", "1.2.0");

            Map<String, Object> initParams = new LinkedHashMap<>();
            initParams.put("protocolVersion", MCP_PROTOCOL_VERSION);
            initParams.put("capabilities", Collections.emptyMap());
            initParams.put("clientInfo", clientInfo);

            Map<String, Object> initRequest = new LinkedHashMap<>();
            initRequest.put("jsonrpc", "2.0");
            initRequest.put("id", "init-" + IdUtil.fastSimpleUUID());
            initRequest.put("method", "initialize");
            initRequest.put("params", initParams);

            String initBody = sendRawJsonRpc(initRequest, false);
            parseAndValidateRpcResult(initBody, "initialize");

            // 3. 发送标准 notifications/initialized 通知（无 id，服务端通常响应 HTTP 200/202/204）
            Map<String, Object> initializedNotification = new LinkedHashMap<>();
            initializedNotification.put("jsonrpc", "2.0");
            initializedNotification.put("method", "notifications/initialized");
            sendRawJsonRpc(initializedNotification, true);

            this.initialized = true;
            log.debug("[AI-MCP] 远程 MCP 服务 [{}] 会话握手完成 (sessionId={})", serverName, mcpSessionId);
        } finally {
            if (sessionLock.isHeldByCurrentThread()) {
                sessionLock.unlock();
            }
        }
    }

    private String sendRawJsonRpc(Map<String, Object> requestPayload, boolean notification) {
        String jsonPayload = JsonUtil.toJsonString(requestPayload);
        String targetUrl = getEffectiveEndpointUrl();
        Object rawId = requestPayload.get("id");
        String requestId = (!notification && rawId != null) ? String.valueOf(rawId) : null;

        CompletableFuture<String> pendingFuture = null;
        if (requestId != null && sseConnected.get()) {
            pendingFuture = new CompletableFuture<>();
            pendingResponses.put(requestId, pendingFuture);
        }

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload));

            if (StringUtils.hasText(apiKey)) {
                builder.header("Authorization", "Bearer " + apiKey.trim());
            }
            if (StringUtils.hasText(mcpSessionId)) {
                builder.header(HEADER_MCP_SESSION_ID, mcpSessionId);
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            captureSessionHeader(response);

            int statusCode = response.statusCode();
            if (statusCode < 200 || statusCode >= 300) {
                if (statusCode == 404 && StringUtils.hasText(mcpSessionId)) {
                    this.mcpSessionId = null;
                    this.initialized = false;
                }
                throw new BusinessException(AiResultCode.AI_MCP_CALL_FAILED, String.format(
                        "调用远程 MCP 服务 [%s] 异常 (HTTP %d, url=%s, body=%s)",
                        serverName, statusCode, targetUrl, response.body()));
            }

            if (notification) {
                return null;
            }

            String body = response.body();
            // Streamable HTTP 可能通过 POST 响应流返回 text/event-stream 格式数据
            if (isEventStreamResponse(response) && StringUtils.hasText(body)) {
                String extracted = extractSseDataForRequest(body, requestId);
                if (StringUtils.hasText(extracted)) {
                    return extracted;
                }
            }

            // Legacy SSE 模式：POST 返回 202 或空响应体，真实结果通过持久化 GET SSE 长连接异步送达
            if ((statusCode == 202 || !StringUtils.hasText(body)) && pendingFuture != null) {
                return awaitSseResponse(requestId, pendingFuture);
            }

            return body;
        } catch (HttpTimeoutException e) {
            throw new BusinessException(AiResultCode.AI_TIMEOUT,
                    String.format("调用远程 MCP 服务 [%s] 超时 (url=%s): %s", serverName, targetUrl, e.getMessage()), e);
        } catch (IOException e) {
            throw new BusinessException(AiResultCode.AI_MCP_CALL_FAILED,
                    String.format("调用远程 MCP 服务 [%s] 网络 IO 失败 (url=%s): %s", serverName, targetUrl, e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(AiResultCode.AI_MCP_CALL_FAILED,
                    String.format("调用远程 MCP 服务 [%s] 被中断: %s", serverName, e.getMessage()), e);
        } finally {
            if (requestId != null) {
                pendingResponses.remove(requestId);
            }
        }
    }

    private String awaitSseResponse(String requestId, CompletableFuture<String> pendingFuture) throws InterruptedException {
        try {
            return pendingFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new BusinessException(AiResultCode.AI_TIMEOUT,
                    String.format("等待 MCP SSE 异步响应超时 [server=%s, id=%s]", serverName, requestId), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(AiResultCode.AI_MCP_CALL_FAILED,
                    String.format("MCP SSE 异步通道异常断开 [server=%s, id=%s]: %s",
                            serverName, requestId, cause != null ? cause.getMessage() : e.getMessage()), cause);
        }
    }

    private void captureSessionHeader(HttpResponse<?> response) {
        HttpHeaders headers = response.headers();
        if (headers == null) {
            return;
        }
        Optional<String> headerVal = headers.firstValue(HEADER_MCP_SESSION_ID);
        if (headerVal.isPresent() && StringUtils.hasText(headerVal.get())) {
            this.mcpSessionId = headerVal.get().trim();
        }
    }

    private boolean isEventStreamResponse(HttpResponse<?> response) {
        HttpHeaders headers = response.headers();
        if (headers == null) {
            return false;
        }
        return headers.firstValue("Content-Type")
                .map(ct -> ct.toLowerCase(Locale.ROOT).contains("text/event-stream"))
                .orElse(false);
    }

    @SuppressWarnings("unchecked")
    private String extractSseDataForRequest(String sseBody, String expectedId) {
        String[] lines = sseBody.replace("\r\n", "\n").split("\n");
        StringBuilder dataBuf = new StringBuilder();
        String fallbackJson = null;

        for (int i = 0; i <= lines.length; i++) {
            String rawLine = i < lines.length ? lines[i] : "";
            String line = rawLine.trim();
            if (line.startsWith("data:")) {
                if (dataBuf.length() > 0) {
                    dataBuf.append("\n");
                }
                dataBuf.append(line.substring(5).trim());
                continue;
            }
            if (line.isEmpty() && dataBuf.length() > 0) {
                String data = dataBuf.toString().trim();
                dataBuf.setLength(0);
                if (data.startsWith("{")) {
                    if (fallbackJson == null) {
                        fallbackJson = data;
                    }
                    Map<String, Object> parsed = JsonUtil.parseObject(data, Map.class);
                    if (parsed != null && expectedId != null && expectedId.equals(String.valueOf(parsed.get("id")))) {
                        return data;
                    }
                }
            }
        }
        return fallbackJson;
    }

    /**
     * 判断当前端点是否为 Legacy SSE 传输端点
     *
     * @return 是否为 SSE 端点
     */
    public boolean isSseEndpoint() {
        if ("sse".equalsIgnoreCase(this.transport)) {
            return true;
        }
        if (!StringUtils.hasText(endpointUrl)) {
            return false;
        }
        String clean = endpointUrl.trim().toLowerCase(Locale.ROOT);
        return clean.endsWith("/sse") || clean.contains("/sse?") || clean.contains("/events");
    }

    /**
     * 获取当前生效的实际 JSON-RPC POST 消息端点。
     *
     * @return 实际 POST 目标 URL
     */
    public String getEffectiveEndpointUrl() {
        if (resolvedPostUrl != null) {
            return resolvedPostUrl;
        }
        sessionLock.lock();
        try {
            if (resolvedPostUrl != null) {
                return resolvedPostUrl;
            }
            if (!isSseEndpoint()) {
                this.resolvedPostUrl = this.endpointUrl;
                return this.resolvedPostUrl;
            }
            this.resolvedPostUrl = startPersistentSseAndAwaitEndpoint();
            return this.resolvedPostUrl;
        } finally {
            if (sessionLock.isHeldByCurrentThread()) {
                sessionLock.unlock();
            }
        }
    }

    private String startPersistentSseAndAwaitEndpoint() {
        log.info("[AI-MCP] 正在向远程 MCP SSE 端点 [{}] 建立持久监听连接...", endpointUrl);
        closeSseStreamInternal();

        CompletableFuture<String> endpointFuture = new CompletableFuture<>();
        this.sseReaderFuture = CompletableFuture.runAsync(
                AsyncContext.wrap(() -> runPersistentSseLoop(endpointFuture)),
                AsyncUtil.getExecutor()
        );

        try {
            long waitMs = Math.min(timeout.toMillis(), 10000L);
            String postUrl = endpointFuture.get(waitMs, TimeUnit.MILLISECONDS);
            return StringUtils.hasText(postUrl) ? postUrl : this.endpointUrl;
        } catch (Exception e) {
            log.warn("[AI-MCP] 远程 MCP 服务 [{}] SSE 握手获取端点异常 ({})，回退使用原始端点", serverName, e.getMessage());
            return this.endpointUrl;
        }
    }

    @SuppressWarnings("unchecked")
    private void runPersistentSseLoop(CompletableFuture<String> endpointFuture) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpointUrl))
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .GET();

        if (StringUtils.hasText(apiKey)) {
            builder.header("Authorization", "Bearer " + apiKey.trim());
        }

        boolean sseStreamErrored = false;
        try {
            HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            captureSessionHeader(response);
            int statusCode = response.statusCode();
            if (statusCode < 200 || statusCode >= 300) {
                log.warn("[AI-MCP] 远程 MCP 服务 [{}] SSE 握手状态码异常: HTTP {}", serverName, statusCode);
                sseStreamErrored = true;
                endpointFuture.complete(this.endpointUrl);
                return;
            }

            InputStream stream = response.body();
            this.activeSseInputStream = stream;
            this.sseConnected.set(true);

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                String currentEvent = null;
                StringBuilder eventData = new StringBuilder();

                while (!closed.get() && (line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("event:")) {
                        currentEvent = trimmed.substring(6).trim();
                    } else if (trimmed.startsWith("data:")) {
                        if (eventData.length() > 0) {
                            eventData.append("\n");
                        }
                        eventData.append(trimmed.substring(5).trim());
                    } else if (trimmed.isEmpty()) {
                        dispatchSseFrame(currentEvent, eventData.toString().trim(), endpointFuture);
                        currentEvent = null;
                        eventData.setLength(0);
                    }
                }
                if (eventData.length() > 0) {
                    dispatchSseFrame(currentEvent, eventData.toString().trim(), endpointFuture);
                }
            }
        } catch (Exception e) {
            sseStreamErrored = true;
            if (!closed.get()) {
                log.warn("[AI-MCP] 远程 MCP 服务 [{}] SSE 监听通道终止: {}", serverName, e.getMessage());
            }
        } finally {
            this.sseConnected.set(false);
            closeQuietly(this.activeSseInputStream);
            this.activeSseInputStream = null;
            if (!endpointFuture.isDone()) {
                endpointFuture.complete(this.endpointUrl);
            }
            if (!closed.get() && (this.initialized || this.sseStreamNeedsReconnect || sseStreamErrored)) {
                this.sseStreamNeedsReconnect = true;
                this.initialized = false;
                this.resolvedPostUrl = null;
            }
            failPendingRequests("MCP SSE 长连接已断开");
        }
    }

    @SuppressWarnings("unchecked")
    private void dispatchSseFrame(String eventName, String data, CompletableFuture<String> endpointFuture) {
        if (!StringUtils.hasText(data)) {
            return;
        }
        if ("endpoint".equalsIgnoreCase(eventName)
                || (!endpointFuture.isDone() && (data.startsWith("/") || data.startsWith("http")))) {
            String postUrl = URI.create(endpointUrl).resolve(data).toString();
            log.info("[AI-MCP] 远程 MCP 服务 [{}] SSE 握手成功，消息投递端点: {}", serverName, postUrl);
            endpointFuture.complete(postUrl);
            return;
        }
        if (data.startsWith("{")) {
            this.sseStreamNeedsReconnect = true;
            Map<String, Object> parsed = JsonUtil.parseObject(data, Map.class);
            if (parsed != null && parsed.get("id") != null) {
                String msgId = String.valueOf(parsed.get("id"));
                CompletableFuture<String> pending = pendingResponses.remove(msgId);
                if (pending != null) {
                    pending.complete(data);
                }
            }
        }
    }

    private void failPendingRequests(String reason) {
        if (pendingResponses.isEmpty()) {
            return;
        }
        for (Map.Entry<String, CompletableFuture<String>> entry : pendingResponses.entrySet()) {
            CompletableFuture<String> future = pendingResponses.remove(entry.getKey());
            if (future != null && !future.isDone()) {
                future.completeExceptionally(new BusinessException(AiResultCode.AI_MCP_CALL_FAILED, reason));
            }
        }
    }

    private void closeSseStreamInternal() {
        this.sseConnected.set(false);
        closeQuietly(this.activeSseInputStream);
        this.activeSseInputStream = null;
        Future<?> reader = this.sseReaderFuture;
        this.sseReaderFuture = null;
        if (reader != null && !reader.isDone()) {
            reader.cancel(true);
        }
    }

    private static void closeQuietly(InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void ensureNotClosed() {
        if (closed.get()) {
            throw new BusinessException(AiResultCode.AI_MCP_CALL_FAILED,
                    String.format("远程 MCP 客户端 [%s] 已关闭，无法发起调用", serverName));
        }
    }

    @Override
    public void destroy() {
        close();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            closeSseStreamInternal();
            failPendingRequests("MCP 客户端已销毁关闭");
            this.initialized = false;
            this.resolvedPostUrl = null;
            this.mcpSessionId = null;
            log.info("[AI-MCP] 已确定性关闭并释放 MCP 客户端连接资源 [server={}]", serverName);
        }
    }

    private String extractTextContent(Object contentObj) {
        if (contentObj == null) {
            return "";
        }
        if (contentObj instanceof String text) {
            return text;
        }
        if (contentObj instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (item instanceof Map<?, ?> itemMap) {
                    Object text = itemMap.get("text");
                    if (text != null) {
                        sb.append(text).append("\n");
                    }
                } else if (item != null) {
                    sb.append(item).append("\n");
                }
            }
            return sb.toString().trim();
        }
        return JsonUtil.toJsonString(contentObj);
    }

    private static String asString(Object val) {
        return val != null ? String.valueOf(val) : null;
    }

    @Override
    public String getServerName() {
        return serverName;
    }

    public String getEndpointUrl() {
        return endpointUrl;
    }
}
