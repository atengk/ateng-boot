package io.github.atengk.boot.ai.mcp;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 HTTP JSON-RPC 2.0 的远程 MCP 客户端适配器实现
 * <p>
 * 纯原生 JDK 17 {@link HttpClient} 实现，零第三方臃肿依赖，
 * 将 Anthropic MCP 服务端暴露的动态工具矩阵透明转译为 LangChain4j 的 {@link ToolSpecification} 与 {@link ToolExecutor}。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class HttpMcpClientAdapter implements McpClientAdapter {

    private static final Logger log = LoggerFactory.getLogger(HttpMcpClientAdapter.class);

    private final String serverName;
    private final String endpointUrl;
    private final Duration timeout;
    private final String apiKey;
    private final HttpClient httpClient;
    private final Map<ToolSpecification, ToolExecutor> toolsMap = new ConcurrentHashMap<>();

    public HttpMcpClientAdapter(String serverName, String endpointUrl) {
        this(serverName, endpointUrl, Duration.ofSeconds(30), null, true);
    }

    public HttpMcpClientAdapter(String serverName, String endpointUrl, Duration timeout, String apiKey, boolean eagerInit) {
        this(serverName, endpointUrl, timeout, apiKey, HttpClient.newBuilder()
                .connectTimeout(timeout != null ? timeout : Duration.ofSeconds(10))
                .build());
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
        Assert.hasText(serverName, "serverName 不能为空");
        Assert.hasText(endpointUrl, "endpointUrl 不能为空");
        this.serverName = serverName.trim();
        this.endpointUrl = endpointUrl.trim();
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(30);
        this.apiKey = apiKey;
        this.httpClient = httpClient != null ? httpClient : HttpClient.newHttpClient();
    }

    @Override
    public Map<ToolSpecification, ToolExecutor> getTools() {
        if (toolsMap.isEmpty()) {
            synchronized (toolsMap) {
                if (toolsMap.isEmpty()) {
                    refreshTools();
                }
            }
        }
        return Collections.unmodifiableMap(toolsMap);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void refreshTools() {
        log.info("[AI-MCP] 正在向远程 MCP 服务 [{}] 发起工具清单发现请求 (tools/list)...", serverName);

        Map<String, Object> rpcRequest = new LinkedHashMap<>();
        rpcRequest.put("jsonrpc", "2.0");
        rpcRequest.put("id", "list-" + IdUtil.fastSimpleUUID());
        rpcRequest.put("method", "tools/list");
        rpcRequest.put("params", Collections.emptyMap());

        String responseBody = sendJsonRpc(rpcRequest);
        Map<String, Object> parsedResponse = JsonUtil.parseObject(responseBody, Map.class);
        if (parsedResponse == null) {
            log.warn("[AI-MCP] 远程 MCP 服务 [{}] 返回空响应", serverName);
            return;
        }

        if (parsedResponse.containsKey("error")) {
            Map<String, Object> error = (Map<String, Object>) parsedResponse.get("error");
            String errorMsg = error != null ? String.valueOf(error.get("message")) : "未知错误";
            throw new BusinessException(String.format("远程 MCP 服务 [%s] 工具发现失败: %s", serverName, errorMsg));
        }

        Map<String, Object> result = (Map<String, Object>) parsedResponse.get("result");
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
            String toolName = (String) toolMap.get("name");
            String description = (String) toolMap.get("description");
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
    @SuppressWarnings("unchecked")
    public String callTool(String toolName, Map<String, Object> arguments) {
        Assert.hasText(toolName, "toolName 不能为空");

        log.debug("[AI-MCP] 正在调用远程 MCP 工具 [server={}, tool={}, args={}]", serverName, toolName, arguments);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName.trim());
        params.put("arguments", arguments != null ? arguments : Collections.emptyMap());

        Map<String, Object> rpcRequest = new LinkedHashMap<>();
        rpcRequest.put("jsonrpc", "2.0");
        rpcRequest.put("id", "call-" + IdUtil.fastSimpleUUID());
        rpcRequest.put("method", "tools/call");
        rpcRequest.put("params", params);

        String responseBody = sendJsonRpc(rpcRequest);
        Map<String, Object> parsed = JsonUtil.parseObject(responseBody, Map.class);
        if (parsed == null) {
            throw new BusinessException(String.format("远程 MCP 服务 [%s] 工具 [%s] 调用返回空响应", serverName, toolName));
        }

        if (parsed.containsKey("error")) {
            Map<String, Object> error = (Map<String, Object>) parsed.get("error");
            String errorMsg = error != null ? String.valueOf(error.get("message")) : "未知错误";
            throw new BusinessException(String.format("MCP 工具 [%s] 执行报错: %s", toolName, errorMsg));
        }

        Map<String, Object> result = (Map<String, Object>) parsed.get("result");
        if (result == null) {
            return "Success";
        }

        // 检查 isError 标记
        if (Boolean.TRUE.equals(result.get("isError"))) {
            String errContent = extractTextContent(result.get("content"));
            throw new BusinessException(String.format("MCP 工具 [%s] 执行失败: %s", toolName, errContent));
        }

        String contentText = extractTextContent(result.get("content"));
        return StringUtils.hasText(contentText) ? contentText : "Success";
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

    private String sendJsonRpc(Map<String, Object> requestPayload) {
        String jsonPayload = JsonUtil.toJsonString(requestPayload);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpointUrl))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload));

        if (StringUtils.hasText(apiKey)) {
            builder.header("Authorization", "Bearer " + apiKey.trim());
        }

        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            if (statusCode < 200 || statusCode >= 300) {
                throw new BusinessException(String.format(
                        "调用远程 MCP 服务 [%s] 异常 (HTTP %d, url=%s, body=%s)",
                        serverName, statusCode, endpointUrl, response.body()));
            }
            return response.body();
        } catch (IOException e) {
            throw new BusinessException(String.format("调用远程 MCP 服务 [%s] 网络 IO 失败: %s", serverName, e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(String.format("调用远程 MCP 服务 [%s] 被中断: %s", serverName, e.getMessage()), e);
        }
    }

    @Override
    public String getServerName() {
        return serverName;
    }

    public String getEndpointUrl() {
        return endpointUrl;
    }
}
