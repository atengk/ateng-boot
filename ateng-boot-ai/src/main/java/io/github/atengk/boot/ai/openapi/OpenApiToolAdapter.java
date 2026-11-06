package io.github.atengk.boot.ai.openapi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.tool.SandboxedToolExecutor;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 企业 OpenAPI 3.0 与 Swagger 2.0 契约转 Agent 受管沙箱工具适配器
 * <p>
 * 具备以下企业级生产特性：
 * 1. 零臃肿解析：100% 依托 Jackson 树模型与 Spring 6 原生 RestClient，杜绝引入第三方重量级解析依赖；
 * 2. 强类型沙箱映射：自动将 HTTP 操作端点转译为 LangChain4j {@link ToolSpecification} 与受管 {@link SandboxedToolExecutor}；
 * 3. 4D 治理上下文透传：跨网络请求自动注入 MDC TraceId、当前用户与租户上下文；
 * 4. 弹性异常反思自愈：遇到网络或业务异常时结构化封装错误摘要反馈大模型，杜绝未捕获异常引发会话崩溃。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
public class OpenApiToolAdapter {

    private static final Logger log = LoggerFactory.getLogger(OpenApiToolAdapter.class);

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final OpenApiToolProperties properties;
    private final String rawSpecJson;
    private final RestClient restClient;

    private final Map<String, OpenApiOperationMeta> operationsMap = new ConcurrentHashMap<>();
    private volatile Map<ToolSpecification, ToolExecutor> sandboxedTools = Collections.emptyMap();
    private final Map<String, ToolExecutor> executorByOperationId = new ConcurrentHashMap<>();

    private JsonNode rootSpecNode;
    private String resolvedBaseUrl;

    /**
     * 构造 OpenAPI 工具适配器
     *
     * @param rawSpecJson 契约原始 JSON 文本
     * @param properties  适配器配置属性
     * @param restClient  定制 RestClient 实例（为空时基于 properties 自动构建）
     */
    public OpenApiToolAdapter(String rawSpecJson, OpenApiToolProperties properties, RestClient restClient) {
        if (!StringUtils.hasText(rawSpecJson)) {
            throw new AiException(AiResultCode.AI_AGENT_CONFIG_ERROR, "OpenAPI 契约内容不能为空");
        }
        this.rawSpecJson = rawSpecJson;
        this.properties = properties != null ? properties : new OpenApiToolProperties();

        // 1. 解析 JSON 契约根节点
        try {
            this.rootSpecNode = JsonUtil.readTree(rawSpecJson);
        } catch (Exception e) {
            throw new AiException(AiResultCode.AI_AGENT_CONFIG_ERROR, "解析 OpenAPI 契约 JSON 语法失败: " + e.getMessage(), e);
        }

        // 2. 确定 baseUrl
        if (StringUtils.hasText(this.properties.getBaseUrl())) {
            this.resolvedBaseUrl = OpenApiSchemaConverter.cleanTrailingSlash(this.properties.getBaseUrl());
        } else {
            String detected = OpenApiSchemaConverter.detectBaseUrl(this.rootSpecNode);
            this.resolvedBaseUrl = StringUtils.hasText(detected) ? detected : "http://localhost:8080";
        }

        // 3. 构造 RestClient
        if (restClient != null) {
            this.restClient = restClient;
        } else {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout((int) this.properties.getConnectTimeout().toMillis());
            requestFactory.setReadTimeout((int) this.properties.getReadTimeout().toMillis());

            this.restClient = RestClient.builder()
                    .baseUrl(this.resolvedBaseUrl)
                    .requestFactory(requestFactory)
                    .build();
        }

        // 4. 解析并注册受管工具
        initializeTools();
    }

    /**
     * 从类路径资源快速加载构建适配器
     *
     * @param resourcePath 类路径资源（如 "openapi/order-api.json"）
     * @return 适配器实例
     */
    public static OpenApiToolAdapter fromClasspath(String resourcePath) {
        return fromClasspath(resourcePath, new OpenApiToolProperties(), null);
    }

    /**
     * 从类路径资源加载构建适配器
     *
     * @param resourcePath 类路径资源
     * @param properties   配置属性
     * @return 适配器实例
     */
    public static OpenApiToolAdapter fromClasspath(String resourcePath, OpenApiToolProperties properties) {
        return fromClasspath(resourcePath, properties, null);
    }

    /**
     * 从类路径资源加载构建适配器并注入自定义 RestClient（便于 Mock 单元测试）
     *
     * @param resourcePath 类路径资源
     * @param properties   配置属性
     * @param restClient   自定义 RestClient
     * @return 适配器实例
     */
    public static OpenApiToolAdapter fromClasspath(String resourcePath, OpenApiToolProperties properties, RestClient restClient) {
        try {
            ClassPathResource resource = new ClassPathResource(resourcePath);
            try (InputStream is = resource.getInputStream()) {
                String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                return new OpenApiToolAdapter(content, properties, restClient);
            }
        } catch (Exception e) {
            throw new AiException(AiResultCode.AI_AGENT_CONFIG_ERROR,
                    "从类路径读取 OpenAPI 契约资源 [" + resourcePath + "] 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从远程 URL 加载构建适配器
     *
     * @param specUrl    OpenAPI 契约访问地址
     * @param properties 配置属性
     * @return 适配器实例
     */
    public static OpenApiToolAdapter fromUrl(String specUrl, OpenApiToolProperties properties) {
        try {
            RestClient client = RestClient.create();
            String content = client.get().uri(specUrl).retrieve().body(String.class);
            return new OpenApiToolAdapter(content, properties, null);
        } catch (Exception e) {
            throw new AiException(AiResultCode.AI_AGENT_CONFIG_ERROR,
                    "从远程 URL 获取 OpenAPI 契约 [" + specUrl + "] 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从原始 JSON 字符串构建适配器
     *
     * @param json       JSON 文本
     * @param properties 配置属性
     * @return 适配器实例
     */
    public static OpenApiToolAdapter fromString(String json, OpenApiToolProperties properties) {
        return new OpenApiToolAdapter(json, properties, null);
    }

    /**
     * 从原始 JSON 字符串构建适配器并注入自定义 RestClient
     *
     * @param json       JSON 文本
     * @param properties 配置属性
     * @param restClient 自定义 RestClient
     * @return 适配器实例
     */
    public static OpenApiToolAdapter fromString(String json, OpenApiToolProperties properties, RestClient restClient) {
        return new OpenApiToolAdapter(json, properties, restClient);
    }

    /**
     * 初始化解析全部工具
     */
    private synchronized void initializeTools() {
        Map<String, OpenApiOperationMeta> localOperationsMap = new ConcurrentHashMap<>();
        Map<ToolSpecification, ToolExecutor> localSandboxedTools = new LinkedHashMap<>();
        Map<String, ToolExecutor> localExecutorByOperationId = new ConcurrentHashMap<>();

        List<OpenApiOperationMeta> operations = OpenApiSchemaConverter.parseOperations(rootSpecNode);
        for (OpenApiOperationMeta op : operations) {
            String opId = op.getOperationId();
            String method = op.getHttpMethod().toUpperCase(Locale.ROOT);

            // 1. 过滤只读模式限制
            if (properties.isReadOnly() && !SAFE_METHODS.contains(method)) {
                log.debug("[AI-OPENAPI-TOOL] 适配器 [{}] 处于只读模式，忽略非安全写操作: {} {}", properties.getName(), method, op.getPath());
                continue;
            }

            // 2. 过滤 HTTP 请求方法白名单
            if (properties.getAllowedMethods() != null && !properties.getAllowedMethods().isEmpty()) {
                if (!properties.getAllowedMethods().contains(method)) {
                    log.debug("[AI-OPENAPI-TOOL] 操作 {} {} 不在允许的方法白名单内，忽略", method, op.getPath());
                    continue;
                }
            }

            // 3. 过滤操作包含白名单与排除黑名单
            if (properties.getIncludeOperations() != null && !properties.getIncludeOperations().isEmpty()) {
                if (!properties.getIncludeOperations().contains(opId)) {
                    continue;
                }
            }
            if (properties.getExcludeOperations() != null && properties.getExcludeOperations().contains(opId)) {
                continue;
            }

            // 4. 转换为 ToolSpecification
            ToolSpecification specification = OpenApiSchemaConverter.toToolSpecification(op, rootSpecNode);

            // 5. 构造执行器并用受管沙箱包装
            ToolExecutor rawExecutor = (request, memoryId) -> executeRequest(op, request);
            ToolExecutor sandboxed = SandboxedToolExecutor.wrap(opId, rawExecutor);

            localOperationsMap.put(opId, op);
            localSandboxedTools.put(specification, sandboxed);
            localExecutorByOperationId.put(opId, sandboxed);

            log.debug("[AI-OPENAPI-TOOL] 适配器 [{}] 成功转换受管工具: [{}] -> {} {}",
                    properties.getName(), opId, method, op.getPath());
        }

        this.operationsMap.clear();
        this.operationsMap.putAll(localOperationsMap);
        this.executorByOperationId.clear();
        this.executorByOperationId.putAll(localExecutorByOperationId);
        this.sandboxedTools = Collections.unmodifiableMap(localSandboxedTools);

        log.info("[AI-OPENAPI-TOOL] 适配器 [{}] 解析完成，共注册 {} 个受管 OpenAPI 沙箱工具 (baseUrl: {})",
                properties.getName(), localSandboxedTools.size(), resolvedBaseUrl);
    }

    /**
     * 获取 OpenAPI 工具映射字典 (ToolSpecification -> ToolExecutor)
     *
     * @return 工具映射字典
     */
    public Map<ToolSpecification, ToolExecutor> getTools() {
        return sandboxedTools;
    }

    /**
     * 获取全部工具规格列表
     *
     * @return 工具规格列表
     */
    public List<ToolSpecification> getToolSpecifications() {
        return new ArrayList<>(sandboxedTools.keySet());
    }

    /**
     * 根据 operationId 获取对应的沙箱工具执行器
     *
     * @param operationId 操作标识
     * @return 沙箱执行器，若不存在返回 null
     */
    public ToolExecutor getToolExecutor(String operationId) {
        return executorByOperationId.get(operationId);
    }

    /**
     * 直接通过 operationId 和结构化入参字典调用目标接口
     *
     * @param operationId 操作唯一标识
     * @param arguments   调用入参字典
     * @return 目标接口返回结果 JSON 文本
     */
    public String execute(String operationId, Map<String, Object> arguments) {
        OpenApiOperationMeta op = operationsMap.get(operationId);
        if (op == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", true);
            err.put("statusCode", 404);
            err.put("message", "未找到操作标识为 [" + operationId + "] 的 OpenAPI 工具定义");
            return JsonUtil.toJsonString(err);
        }
        return doExecute(op, arguments != null ? arguments : Collections.emptyMap());
    }

    /**
     * 处理 LangChain4j 工具执行请求
     */
    private String executeRequest(OpenApiOperationMeta op, ToolExecutionRequest request) {
        Map<String, Object> arguments = Collections.emptyMap();
        if (request != null && StringUtils.hasText(request.arguments())) {
            try {
                arguments = JsonUtil.parseMap(request.arguments());
            } catch (Exception e) {
                log.warn("[AI-OPENAPI-TOOL] 解析大模型工具入参失败: {}, raw: {}", e.getMessage(), request.arguments());
            }
        }
        return doExecute(op, arguments);
    }

    /**
     * 执行底层 HTTP 调度
     */
    private String doExecute(OpenApiOperationMeta op, Map<String, Object> arguments) {
        String operationId = op.getOperationId();
        String method = op.getHttpMethod();
        String pathTemplate = op.getPath();

        try {
            // 1. 替换 Path 变量
            String resolvedPath = pathTemplate;
            List<OpenApiOperationMeta.ParameterMeta> pathParams = op.getParametersByIn("path");
            Set<String> consumedArgKeys = new HashSet<>();

            for (OpenApiOperationMeta.ParameterMeta p : pathParams) {
                String name = p.name();
                Object val = arguments.get(name);
                if (val != null) {
                    consumedArgKeys.add(name);
                    String encodedVal = URLEncoder.encode(String.valueOf(val), StandardCharsets.UTF_8);
                    resolvedPath = resolvedPath.replace("{" + name + "}", encodedVal);
                } else if (p.required()) {
                    log.warn("[AI-OPENAPI-TOOL] 缺少必填 Path 参数: [{}] for operation [{}]", name, operationId);
                }
            }

            // 2. 收集 Query 参数并合成目标 URI
            String targetPath = resolvedPath.startsWith("/") ? resolvedPath : "/" + resolvedPath;
            String fullUrl = (StringUtils.hasText(resolvedBaseUrl) ? resolvedBaseUrl : "") + targetPath;
            UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(fullUrl);
            List<OpenApiOperationMeta.ParameterMeta> queryParams = op.getParametersByIn("query");
            for (OpenApiOperationMeta.ParameterMeta p : queryParams) {
                String name = p.name();
                if (arguments.containsKey(name)) {
                    consumedArgKeys.add(name);
                    Object val = arguments.get(name);
                    if (val != null) {
                        if (val instanceof Collection<?> coll) {
                            for (Object item : coll) {
                                if (item != null) {
                                    uriBuilder.queryParam(name, String.valueOf(item));
                                }
                            }
                        } else {
                            uriBuilder.queryParam(name, String.valueOf(val));
                        }
                    }
                }
            }

            URI targetUri = uriBuilder.build().encode().toUri();

            // 3. 构造 RequestSpec 并填充 Headers
            RestClient.RequestBodySpec requestSpec = restClient
                    .method(HttpMethod.valueOf(method))
                    .uri(targetUri)
                    .accept(MediaType.APPLICATION_JSON, MediaType.ALL);

            // 默认配置请求头
            properties.getDefaultHeaders().forEach(requestSpec::header);

            // 4D 治理上下文透传
            if (properties.isPropagateContext()) {
                String traceId = MDC.get("traceId");
                if (StringUtils.hasText(traceId)) {
                    requestSpec.header(properties.getTraceHeader(), traceId);
                }
                String userId = UserContextHolder.getUserId();
                if (StringUtils.hasText(userId)) {
                    requestSpec.header(properties.getUserHeader(), userId);
                }
                String tenantId = TenantContextHolder.getTenantId();
                if (StringUtils.hasText(tenantId)) {
                    requestSpec.header(properties.getTenantHeader(), tenantId);
                }
            }

            // 静态或动态 Auth 请求头
            if (StringUtils.hasText(properties.getAuthToken())) {
                requestSpec.header(properties.getAuthHeader(), properties.getAuthToken());
            } else if (properties.isPropagateContext()) {
                String dynamicAuth = resolveDynamicAuthorization();
                if (StringUtils.hasText(dynamicAuth)) {
                    requestSpec.header(properties.getAuthHeader(), dynamicAuth);
                }
            }

            // 契约声明的 Header 参数
            List<OpenApiOperationMeta.ParameterMeta> headerParams = op.getParametersByIn("header");
            for (OpenApiOperationMeta.ParameterMeta p : headerParams) {
                String name = p.name();
                if (arguments.containsKey(name)) {
                    consumedArgKeys.add(name);
                    Object val = arguments.get(name);
                    if (val != null) {
                        requestSpec.header(name, String.valueOf(val));
                    }
                }
            }

            // 4. 处理 RequestBody
            if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
                Object bodyPayload = resolveRequestBodyPayload(arguments, consumedArgKeys);
                if (bodyPayload != null) {
                    requestSpec.contentType(MediaType.APPLICATION_JSON);
                    if (bodyPayload instanceof String strBody) {
                        requestSpec.body(strBody);
                    } else {
                        requestSpec.body(JsonUtil.toJsonString(bodyPayload));
                    }
                }
            }

            // 5. 发送请求并弹性接收响应
            ResponseEntity<String> response = requestSpec.retrieve().toEntity(String.class);
            String responseBody = response.getBody();
            if (!StringUtils.hasText(responseBody)) {
                return "{\"status\":\"SUCCESS\",\"statusCode\":" + response.getStatusCode().value() + "}";
            }
            return responseBody;

        } catch (RestClientResponseException e) {
            log.warn("[AI-OPENAPI-TOOL] 远程服务响应错误 [{}]: status={}, body={}",
                    operationId, e.getStatusCode(), e.getResponseBodyAsString());
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", true);
            err.put("statusCode", e.getStatusCode().value());
            err.put("message", "HTTP " + e.getStatusCode().value() + " " + e.getStatusText());
            if (StringUtils.hasText(e.getResponseBodyAsString())) {
                try {
                    err.put("details", JsonUtil.parseObject(e.getResponseBodyAsString(), Object.class));
                } catch (Exception parseEx) {
                    err.put("details", e.getResponseBodyAsString());
                }
            }
            return JsonUtil.toJsonString(err);

        } catch (ResourceAccessException e) {
            log.error("[AI-OPENAPI-TOOL] 网络通信或超时错误 [{}]: {}", operationId, e.getMessage());
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", true);
            err.put("statusCode", 504);
            err.put("message", "Network timeout or connect error: " + e.getMessage());
            return JsonUtil.toJsonString(err);

        } catch (Exception e) {
            log.error("[AI-OPENAPI-TOOL] 执行工具调度发生未知异常 [{}]: {}", operationId, e.getMessage(), e);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", true);
            err.put("statusCode", 500);
            err.put("message", "Internal tool execution error: " + e.getMessage());
            return JsonUtil.toJsonString(err);
        }
    }

    /**
     * 智能推断与提取 RequestBody 负载
     */
    private Object resolveRequestBodyPayload(Map<String, Object> arguments, Set<String> consumedArgKeys) {
        if (arguments == null || arguments.isEmpty()) {
            return null;
        }

        // 1. 若显式存在 body 或 requestBody 字段
        if (arguments.containsKey("body")) {
            return arguments.get("body");
        }
        if (arguments.containsKey("requestBody")) {
            return arguments.get("requestBody");
        }

        // 2. 将非 Path / Query / Header 的入参聚合为 JSON 对象
        Map<String, Object> leftover = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if (!consumedArgKeys.contains(entry.getKey())) {
                leftover.put(entry.getKey(), entry.getValue());
            }
        }

        return leftover.isEmpty() ? null : leftover;
    }

    /**
     * 重新解析契约文档并刷新工具注册表
     */
    public synchronized void reload() {
        initializeTools();
    }

    public String getName() {
        return properties.getName();
    }

    public String getResolvedBaseUrl() {
        return resolvedBaseUrl;
    }

    public OpenApiToolProperties getProperties() {
        return properties;
    }

    private String resolveDynamicAuthorization() {
        try {
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                java.lang.reflect.Method getReqMethod = attrs.getClass().getMethod("getRequest");
                Object req = getReqMethod.invoke(attrs);
                if (req != null) {
                    java.lang.reflect.Method getHeaderMethod = req.getClass().getMethod("getHeader", String.class);
                    Object auth = getHeaderMethod.invoke(req, properties.getAuthHeader());
                    if (auth != null && StringUtils.hasText(auth.toString())) {
                        return auth.toString().trim();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
