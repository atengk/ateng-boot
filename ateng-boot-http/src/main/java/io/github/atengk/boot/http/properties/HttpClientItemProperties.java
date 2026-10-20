package io.github.atengk.boot.http.properties;

import lombok.Data;

import java.time.Duration;

/**
 * 命名 HTTP 客户端专属个性化配置项（用于覆盖全局默认配置）
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Data
public class HttpClientItemProperties {

    /**
     * 目标远端服务的基础请求根路径（Base URL，例如：https://api.example.com）
     */
    private String baseUrl;

    /**
     * 该客户端专属的建联超时时间（若为空则继承全局 pool.connectTimeout）
     */
    private Duration connectTimeout;

    /**
     * 该客户端专属的读取响应超时时间（若为空则继承全局 pool.readTimeout）
     */
    private Duration readTimeout;

    /**
     * 是否允许向该目标服务透传当前上下文请求头（TraceId、TenantId、UserId，默认：true）
     * 对接公网非信任第三方（如微信、支付宝、外部 SaaS）时强制显式配置为 false，防止内网敏感信息外泄
     */
    private boolean propagateContext = true;
}
