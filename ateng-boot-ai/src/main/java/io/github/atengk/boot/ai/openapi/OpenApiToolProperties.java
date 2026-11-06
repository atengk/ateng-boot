package io.github.atengk.boot.ai.openapi;

import java.time.Duration;
import java.util.*;

/**
 * OpenAPI / Swagger 智能体工具适配器配置属性
 *
 * @author Ateng
 * @since 2026-11-06
 */
public class OpenApiToolProperties {

    /**
     * 目标服务根路径基地址（未指定时尝试从契约文档的 servers[0].url 或 host + basePath 探测）
     */
    private String baseUrl;

    /**
     * 适配器实例标识名称（用于日志追踪与元数据命名）
     */
    private String name = "openapi-service";

    /**
     * HTTP 连接建立超时时间（默认 5 秒）
     */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /**
     * HTTP 响应读取超时时间（默认 30 秒）
     */
    private Duration readTimeout = Duration.ofSeconds(30);

    /**
     * 全局默认请求头映射
     */
    private Map<String, String> defaultHeaders = new LinkedHashMap<>();

    /**
     * 包含操作白名单集合（为空时包含契约中的全部操作）
     */
    private Set<String> includeOperations = new LinkedHashSet<>();

    /**
     * 排除操作黑名单集合
     */
    private Set<String> excludeOperations = new LinkedHashSet<>();

    /**
     * 允许调用的 HTTP 请求方法集合（默认支持 GET, POST, PUT, DELETE, PATCH）
     */
    private Set<String> allowedMethods = new LinkedHashSet<>(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH"));

    /**
     * 是否只读模式（若开启则仅允许幂等安全方法 GET、HEAD、OPTIONS）
     */
    private boolean readOnly = false;

    /**
     * 是否全链路透传 4D 上下文（TraceId、UserId、TenantId 等）
     */
    private boolean propagateContext = true;

    /**
     * 全链路追踪 MDC TraceId 请求头名称
     */
    private String traceHeader = "X-Trace-Id";

    /**
     * 当前登录用户上下文 UserId 请求头名称
     */
    private String userHeader = "X-User-Id";

    /**
     * 当前多租户上下文 TenantId 请求头名称
     */
    private String tenantHeader = "X-Tenant-Id";

    /**
     * 鉴权令牌请求头名称
     */
    private String authHeader = "Authorization";

    /**
     * 静态鉴权令牌值（可选，如 Bearer xxx）
     */
    private String authToken;

    public OpenApiToolProperties() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public Map<String, String> getDefaultHeaders() {
        return defaultHeaders;
    }

    public void setDefaultHeaders(Map<String, String> defaultHeaders) {
        this.defaultHeaders = defaultHeaders != null ? defaultHeaders : new LinkedHashMap<>();
    }

    public Set<String> getIncludeOperations() {
        return includeOperations;
    }

    public void setIncludeOperations(Set<String> includeOperations) {
        this.includeOperations = includeOperations != null ? includeOperations : new LinkedHashSet<>();
    }

    public Set<String> getExcludeOperations() {
        return excludeOperations;
    }

    public void setExcludeOperations(Set<String> excludeOperations) {
        this.excludeOperations = excludeOperations != null ? excludeOperations : new LinkedHashSet<>();
    }

    public Set<String> getAllowedMethods() {
        return allowedMethods;
    }

    public void setAllowedMethods(Set<String> allowedMethods) {
        this.allowedMethods = allowedMethods != null ? allowedMethods : new LinkedHashSet<>();
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public boolean isPropagateContext() {
        return propagateContext;
    }

    public void setPropagateContext(boolean propagateContext) {
        this.propagateContext = propagateContext;
    }

    public String getTraceHeader() {
        return traceHeader;
    }

    public void setTraceHeader(String traceHeader) {
        this.traceHeader = traceHeader;
    }

    public String getUserHeader() {
        return userHeader;
    }

    public void setUserHeader(String userHeader) {
        this.userHeader = userHeader;
    }

    public String getTenantHeader() {
        return tenantHeader;
    }

    public void setTenantHeader(String tenantHeader) {
        this.tenantHeader = tenantHeader;
    }

    public String getAuthHeader() {
        return authHeader;
    }

    public void setAuthHeader(String authHeader) {
        this.authHeader = authHeader;
    }

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    /**
     * 链式构建器
     */
    public static class Builder {
        private final OpenApiToolProperties properties = new OpenApiToolProperties();

        public Builder baseUrl(String baseUrl) {
            properties.setBaseUrl(baseUrl);
            return this;
        }

        public Builder name(String name) {
            properties.setName(name);
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            properties.setConnectTimeout(connectTimeout);
            return this;
        }

        public Builder readTimeout(Duration readTimeout) {
            properties.setReadTimeout(readTimeout);
            return this;
        }

        public Builder defaultHeaders(Map<String, String> defaultHeaders) {
            properties.setDefaultHeaders(defaultHeaders);
            return this;
        }

        public Builder addHeader(String key, String value) {
            properties.getDefaultHeaders().put(key, value);
            return this;
        }

        public Builder includeOperations(Set<String> includeOperations) {
            properties.setIncludeOperations(includeOperations);
            return this;
        }

        public Builder addIncludeOperation(String operationId) {
            properties.getIncludeOperations().add(operationId);
            return this;
        }

        public Builder excludeOperations(Set<String> excludeOperations) {
            properties.setExcludeOperations(excludeOperations);
            return this;
        }

        public Builder addExcludeOperation(String operationId) {
            properties.getExcludeOperations().add(operationId);
            return this;
        }

        public Builder allowedMethods(Set<String> allowedMethods) {
            properties.setAllowedMethods(allowedMethods);
            return this;
        }

        public Builder readOnly(boolean readOnly) {
            properties.setReadOnly(readOnly);
            return this;
        }

        public Builder propagateContext(boolean propagateContext) {
            properties.setPropagateContext(propagateContext);
            return this;
        }

        public Builder traceHeader(String traceHeader) {
            properties.setTraceHeader(traceHeader);
            return this;
        }

        public Builder userHeader(String userHeader) {
            properties.setUserHeader(userHeader);
            return this;
        }

        public Builder tenantHeader(String tenantHeader) {
            properties.setTenantHeader(tenantHeader);
            return this;
        }

        public Builder authHeader(String authHeader) {
            properties.setAuthHeader(authHeader);
            return this;
        }

        public Builder authToken(String authToken) {
            properties.setAuthToken(authToken);
            return this;
        }

        public OpenApiToolProperties build() {
            return properties;
        }
    }
}
