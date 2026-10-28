package io.github.atengk.boot.http.interceptor;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.http.constant.HttpConstants;
import io.github.atengk.boot.http.properties.HttpClientItemProperties;
import io.github.atengk.boot.http.properties.HttpProperties;
import io.github.atengk.boot.http.properties.HttpTracingProperties;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 出站 HTTP 请求四维上下文透传与非信任域隔离拦截器
 * 负责在出站物理请求前自动提取当前线程的 MDC traceId、多租户上下文与用户身份标识注入请求头，
 * 并依据目标服务是否属于外部公网非信任域自动执行私有敏感头安全剥离，防止内部租户与用户信息泄露至公网
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
public class TraceContextInterceptor implements ClientHttpRequestInterceptor {

    private static final String MDC_TRACE_ID_KEY = "traceId";

    private final HttpProperties properties;

    public TraceContextInterceptor(HttpProperties properties) {
        this.properties = properties != null ? properties : new HttpProperties();
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        HttpHeaders headers = request.getHeaders();

        // 1. 读取并严格剥离内部控制头（绝对禁止泄露给外部网络物理层，无论是否启用链路追踪）
        Boolean explicitPropagate = HttpInterceptorUtil.extractBooleanHeader(headers, HttpConstants.HEADER_PROPAGATE_CONTEXT_OVERRIDE);
        String explicitClientName = HttpInterceptorUtil.extractStringHeader(headers, HttpConstants.HEADER_CLIENT_NAME_OVERRIDE);

        HttpTracingProperties tracing = properties.getTracing();
        if (tracing == null || !tracing.isEnabled()) {
            return execution.execute(request, body);
        }

        // 2. 双轨自适应判定当前请求是否允许透传私有业务上下文
        boolean propagate = resolvePropagateContext(request.getURI(), explicitPropagate, explicitClientName);

        // 3. 链路追踪 traceId 处理（MDC 为空时通过 IdUtil 自动生成短 UUID 兜底，保障全链路可观测性始终闭环）
        String traceIdHeader = tracing.getTraceIdHeader();
        if (StringUtils.hasText(traceIdHeader) && !headers.containsKey(traceIdHeader)) {
            String traceId = MDC.get(MDC_TRACE_ID_KEY);
            if (!StringUtils.hasText(traceId)) {
                traceId = IdUtil.fastSimpleUUID();
            }
            headers.set(traceIdHeader, traceId);
        }

        // 4. 四维上下文中的敏感业务身份（租户标识、用户标识与忽略租户标志）
        String tenantHeader = tracing.getTenantIdHeader();
        String userHeader = tracing.getUserIdHeader();
        String ignoreTenantHeader = tracing.getIgnoreTenantHeader();

        if (propagate) {
            // 内部信任系统：安全注入多租户与用户上下文
            if (StringUtils.hasText(tenantHeader) && !headers.containsKey(tenantHeader)) {
                String tenantId = TenantContextHolder.getTenantId();
                if (StringUtils.hasText(tenantId)) {
                    headers.set(tenantHeader, tenantId);
                }
            }
            if (StringUtils.hasText(userHeader) && !headers.containsKey(userHeader)) {
                String userId = UserContextHolder.getUserId();
                if (StringUtils.hasText(userId)) {
                    headers.set(userHeader, userId);
                }
            }
            if (StringUtils.hasText(ignoreTenantHeader) && !headers.containsKey(ignoreTenantHeader)) {
                if (TenantContextHolder.isIgnoreTenant()) {
                    headers.set(ignoreTenantHeader, "true");
                }
            }
        } else {
            // 外部非信任域（微信、支付宝、第三方 SaaS 等）：严格物理剥离内网私有敏感标识
            if (StringUtils.hasText(tenantHeader)) {
                headers.remove(tenantHeader);
            }
            if (StringUtils.hasText(userHeader)) {
                headers.remove(userHeader);
            }
            if (StringUtils.hasText(ignoreTenantHeader)) {
                headers.remove(ignoreTenantHeader);
            }
        }

        return execution.execute(request, body);
    }

    /**
     * 依据显式参数、URL 最长前缀匹配与命名客户端策略判定是否透传私有业务上下文
     *
     * @param uri                目标请求绝对 URI
     * @param explicitPropagate  RequestOptions 显式指定的上下文透传开关
     * @param explicitClientName RequestOptions 显式指定的命名客户端名称
     * @return true 允许透传（内部服务），false 严格剥离（外部非信任服务）
     */
    protected boolean resolvePropagateContext(URI uri, Boolean explicitPropagate, String explicitClientName) {
        // 1. 若调用方在单次 RequestOptions 中显式声明，以显式声明为最高优先级
        if (explicitPropagate != null) {
            return explicitPropagate;
        }

        Map<String, HttpClientItemProperties> clients = properties.getClients();
        if (clients == null || clients.isEmpty()) {
            return true; // 默认内部微服务
        }

        // 2. 若单次调用显式指定了客户端名称，按客户端专属配置快速定位
        if (StringUtils.hasText(explicitClientName)) {
            HttpClientItemProperties item = clients.get(explicitClientName);
            if (item != null) {
                return item.isPropagateContext();
            }
        }

        // 3. 依据目标请求 URL 进行最长前缀自适应匹配 (Longest Prefix Match)
        Map.Entry<String, HttpClientItemProperties> matched = HttpInterceptorUtil.matchClientByUrl(uri, clients);
        if (matched != null && matched.getValue() != null) {
            return matched.getValue().isPropagateContext();
        }

        // 4. 未匹配到外部配置，保底为内部信任（true）
        return true;
    }
}
