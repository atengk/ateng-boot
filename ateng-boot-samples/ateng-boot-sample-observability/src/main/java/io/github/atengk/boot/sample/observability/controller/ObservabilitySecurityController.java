package io.github.atengk.boot.sample.observability.controller;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.observability.interceptor.AdminClientSecretHttpRequestInterceptor;
import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import io.github.atengk.boot.sample.observability.listener.ObservabilityAlertEventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 端点安全加固与运维审计领域控制器
 * <p>
 * 提供白名单放行体验、加固访问拦截模拟、SBA 握手请求头自检以及最近捕获的运维领域事件回溯查询接口
 * </p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@RestController
@RequestMapping("/sample/observability/security")
public class ObservabilitySecurityController {

    private final ObservabilityAlertEventListener alertEventListener;
    private final ObservabilityProperties observabilityProperties;

    public ObservabilitySecurityController(ObservabilityAlertEventListener alertEventListener,
                                           ObservabilityProperties observabilityProperties) {
        this.alertEventListener = alertEventListener;
        this.observabilityProperties = observabilityProperties;
    }

    /**
     * 开放白名单巡检端点（不受 Actuator 加固拦截影响）
     */
    @GetMapping("/public-ping")
    public Result<String> publicPing() {
        return Result.success("可观测性服务正常在线，白名单业务端点畅通");
    }

    /**
     * 加固拦截指引提示
     */
    @GetMapping("/simulate-lockdown")
    public Result<String> simulateLockdown() {
        return Result.success("高危端点（/actuator/env, /actuator/beans）已加固：外网访问需携带 X-Actuator-Token 或 Basic Auth 凭据，CIDR 白名单内网免密放行");
    }

    /**
     * SBA 客户端出站握手密钥拦截器自检接口（纯内存模拟出站请求，无需连接外部 SBA Server）
     *
     * @param clientSecret 可选测试密钥；未传入时自动回退读取配置项 {@code admin-client.client-secret}
     * @return {@code X-Admin-Client-Secret} 请求头注入状态与脱敏摘要
     */
    @GetMapping("/admin-handshake-check")
    public Result<Map<String, Object>> adminHandshakeCheck(
            @RequestParam(value = "clientSecret", required = false) String clientSecret) {
        ObservabilityProperties.AdminClientProperties adminClient = observabilityProperties.getAdminClient();
        boolean adminClientEnabled = adminClient != null && adminClient.isEnabled();
        String effectiveSecret = StringUtils.hasText(clientSecret)
                ? clientSecret
                : (adminClient != null ? adminClient.getClientSecret() : null);

        AdminClientSecretHttpRequestInterceptor interceptor = new AdminClientSecretHttpRequestInterceptor(effectiveSecret);
        HttpHeaders headers = new HttpHeaders();
        HttpRequest outboundRequest = new InMemoryOutboundHttpRequest(headers);
        try (ClientHttpResponse ignored = interceptor.intercept(outboundRequest, new byte[0], (req, body) -> EMPTY_OK_RESPONSE)) {
            // 仅验证出站拦截器注入的请求头现场，响应体为离线内存空桩
        } catch (IOException ex) {
            throw new BusinessException("SBA 握手请求头自检执行失败", ex);
        }

        String injectedValue = headers.getFirst(AdminClientSecretHttpRequestInterceptor.CLIENT_SECRET_HEADER);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("adminClientEnabled", adminClientEnabled);
        summary.put("headerName", AdminClientSecretHttpRequestInterceptor.CLIENT_SECRET_HEADER);
        summary.put("headerInjected", StringUtils.hasText(injectedValue));
        summary.put("maskedHeaderValue", StringUtils.hasText(injectedValue) ? DesensitizeUtil.secretKey(injectedValue.strip()) : "");
        return Result.success(summary);
    }

    /**
     * 查询系统最近捕获的运维领域事件列表（线程池拒绝、探针降级、未授权访问阻断）
     */
    @GetMapping("/recent-events")
    public Result<List<Map<String, Object>>> getRecentEvents() {
        return Result.success(alertEventListener.getRecentEvents());
    }

    private static final ClientHttpResponse EMPTY_OK_RESPONSE = new ClientHttpResponse() {
        @Override
        public HttpStatusCode getStatusCode() {
            return HttpStatus.OK;
        }

        @Override
        public String getStatusText() {
            return HttpStatus.OK.getReasonPhrase();
        }

        @Override
        public void close() {
            // 内存桩无物理流资源需关闭
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public HttpHeaders getHeaders() {
            return HttpHeaders.EMPTY;
        }
    };

    private static final class InMemoryOutboundHttpRequest implements HttpRequest {

        private static final URI TARGET_URI = URI.create("http://localhost:9090/instances");
        private final HttpHeaders headers;

        private InMemoryOutboundHttpRequest(HttpHeaders headers) {
            this.headers = headers;
        }

        @Override
        public HttpMethod getMethod() {
            return HttpMethod.POST;
        }

        @Override
        public URI getURI() {
            return TARGET_URI;
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }
    }
}
