package io.github.atengk.boot.http.properties;

import lombok.Data;

/**
 * HTTP 链路追踪与四维上下文出站透传配置属性类
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Data
public class HttpTracingProperties {

    /**
     * 是否启用四维上下文出站透传拦截器（默认：true）
     */
    private boolean enabled = true;

    /**
     * 链路追踪标识请求头键名（默认：X-Trace-Id）
     */
    private String traceIdHeader = "X-Trace-Id";

    /**
     * 多租户标识请求头键名（默认：X-Tenant-Id）
     */
    private String tenantIdHeader = "X-Tenant-Id";

    /**
     * 当前登录用户标识请求头键名（默认：X-User-Id）
     */
    private String userIdHeader = "X-User-Id";

    /**
     * 忽略租户过滤标识请求头键名（默认：X-Ignore-Tenant）
     */
    private String ignoreTenantHeader = "X-Ignore-Tenant";
}
