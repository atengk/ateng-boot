package io.github.atengk.boot.sample.http.service;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.http.error.HttpRemoteException;
import io.github.atengk.boot.http.factory.RestClientRegistry;
import io.github.atengk.boot.http.options.RequestOptions;
import io.github.atengk.boot.http.properties.HttpClientItemProperties;
import io.github.atengk.boot.http.properties.HttpProperties;
import io.github.atengk.boot.http.properties.HttpProxyProperties;
import io.github.atengk.boot.http.properties.HttpSslProperties;
import io.github.atengk.boot.http.template.HttpTemplate;
import io.github.atengk.boot.sample.http.client.WechatPayClient;
import io.github.atengk.boot.sample.http.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 外部支付与治理防御业务场景服务
 * 演示向外部非信任公网发起请求时安全剥离敏感头、瞬态 503 指数退避重试、出站 cURL 脱敏日志、错误体转译及多实例注册治理
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalPaymentScenarioService {

    private final WechatPayClient wechatPayClient;
    private final HttpTemplate httpTemplate;
    private final RestClientRegistry restClientRegistry;
    private final HttpProperties httpProperties;

    @Autowired(required = false)
    private ServletWebServerApplicationContext webServerContext;

    /**
     * 获取当前服务回环访问基地址
     */
    public String getBaseUrl() {
        int port = (webServerContext != null && webServerContext.getWebServer() != null)
                ? webServerContext.getWebServer().getPort()
                : 8080;
        return "http://localhost:" + port;
    }

    /**
     * 1. 外部微信统一下单并验证非信任域内网敏感身份头物理剥离
     */
    public WechatPaymentVO createWechatOrder(WechatNativeOrderRequest request) {
        UserContextHolder.UserSnapshot previousUser = UserContextHolder.capture();
        TenantContextHolder.TenantSnapshot previousTenant = TenantContextHolder.capture();
        String previousTraceId = MDC.get("traceId");
        boolean mdcInjected = false;
        String currentTraceId = previousTraceId;
        if (currentTraceId == null || currentTraceId.isBlank()) {
            currentTraceId = IdUtil.fastSimpleUUID();
            MDC.put("traceId", currentTraceId);
            mdcInjected = true;
        }

        TenantContextHolder.setTenantId("TENANT_SAMPLE_888");
        UserContextHolder.setUserId("USER_SAMPLE_9999");
        try {
            log.info("[PaymentService] Invoking WechatPayClient with active MDC traceId={}, TenantId={}, UserId={}",
                    currentTraceId, TenantContextHolder.getTenantId(), UserContextHolder.getUserId());
            WechatNativeOrderResponse response = wechatPayClient.createNativeOrder(request);

            // 读取仿真接收端录制的物理请求头
            String checkUrl = getBaseUrl() + "/mock/provider/wechat/last-headers";
            @SuppressWarnings("unchecked")
            Map<String, String> headers = httpTemplate.get(checkUrl, Map.class);

            boolean tenantStripped = !headers.containsKey("x-tenant-id");
            boolean userStripped = !headers.containsKey("x-user-id");
            boolean traceRetained = headers.containsKey("x-trace-id");

            log.info("[PaymentService] Header isolation check: tenantStripped={}, userStripped={}, traceRetained={}",
                    tenantStripped, userStripped, traceRetained);

            return WechatPaymentVO.builder()
                    .outTradeNo(request.getOutTradeNo())
                    .codeUrl(response.getCodeUrl())
                    .contextStripped(tenantStripped && userStripped)
                    .traceId(headers.get("x-trace-id"))
                    .build();
        } finally {
            TenantContextHolder.restore(previousTenant);
            UserContextHolder.restore(previousUser);
            if (mdcInjected) {
                MDC.remove("traceId");
            }
        }
    }

    /**
     * 2. 模拟瞬态 503 故障触发指数退避重试 (带 RFC Retry-After 与随机抖动)
     */
    public RetrySimulationVO simulateFlakyRetry(int failTimes) {
        // 先调用重置端点清零计数器
        httpTemplate.get(getBaseUrl() + "/mock/provider/flaky/reset", Map.class);

        String url = getBaseUrl() + "/mock/provider/flaky?failTimes=" + failTimes;
        log.info("[PaymentService] Invoking flaky URL with retry: {}", url);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = httpTemplate.get(url, Map.class);

        int attempts = ((Number) result.get("successfulAttempt")).intValue();
        return RetrySimulationVO.builder()
                .status((String) result.get("status"))
                .totalAttempts(attempts)
                .message("成功完成指数退避重试并在第 " + attempts + " 次请求自愈恢复")
                .build();
    }

    /**
     * 3. 出站 cURL 敏感凭据自动脱敏日志演示
     */
    public LoggingDemoVO triggerMaskedLoggingDemo(String sensitiveToken, String password) {
        String url = getBaseUrl() + "/mock/provider/headers/echo";
        RequestOptions options = RequestOptions.builder()
                .header("Authorization", "Bearer " + sensitiveToken)
                .header("X-Token", sensitiveToken)
                .queryParam("token", sensitiveToken)
                .queryParam("password", password)
                .build();

        log.info("[PaymentService] Triggering masked logging demo against {}", url);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = httpTemplate.get(url, options, Map.class);

        return LoggingDemoVO.builder()
                .requestUrl(url + "?token=******&password=******")
                .maskedNotice("出站请求 Authorization/X-Token 请求头及 token/password 参数已在控制台日志中自动掩码脱敏")
                .status("SUCCESS")
                .build();
    }

    /**
     * 4. 远端异常因果转译与 HttpRemoteException#getResponseBodyAs 结构化错误体提取
     *
     * @param statusCode 期望模拟触发的远端 HTTP 错误状态码（默认 422）
     * @return 错误转译及结构化错误体反序列化结果
     */
    public ErrorTranslationVO demonstrateErrorTranslation(int statusCode) {
        String url = getBaseUrl() + "/mock/provider/errors/" + statusCode;
        log.info("[PaymentService] Demonstrating remote error translation via {}", url);
        try {
            httpTemplate.get(url, Map.class);
            throw new IllegalStateException("Expected HttpRemoteException from " + url);
        } catch (HttpRemoteException ex) {
            RemoteErrorPayload payload = ex.getResponseBodyAs(RemoteErrorPayload.class);
            return ErrorTranslationVO.builder()
                    .mappedResultCode(ex.getResultCode().getCode())
                    .mappedResultMessage(ex.getResultCode().getMsg())
                    .httpStatusCode(ex.getHttpStatusCode())
                    .requestUrl(url)
                    .remoteErrorCode(payload != null ? payload.getErrorCode() : null)
                    .remoteErrorMessage(payload != null ? payload.getErrorMessage() : null)
                    .remoteRequestId(payload != null ? payload.getRequestId() : null)
                    .responseBodyTranslated(payload != null && StringUtils.hasText(payload.getErrorCode()))
                    .build();
        }
    }

    /**
     * 5. RestClientRegistry 多实例、mTLS 与实例级代理治理元数据自省探针
     *
     * @return 所有已配置或已注册命名客户端的治理元数据快照（不含任何凭据明文）
     */
    public RegistryInspectVO inspectRegistryMetadata() {
        // 确保示例配置文件中声明的实例（含 scoped-proxy-client 与 mtls-service）已完成按需注册初始化
        Map<String, HttpClientItemProperties> configuredClients = httpProperties.getClients();
        if (configuredClients != null) {
            configuredClients.keySet().forEach(name -> {
                if (!restClientRegistry.contains(name)) {
                    restClientRegistry.getClient(name);
                }
            });
        }

        List<String> registeredNames = new ArrayList<>(restClientRegistry.getClientNames());
        Collections.sort(registeredNames);

        List<RegistryClientItemVO> items = new ArrayList<>();
        if (configuredClients != null) {
            List<String> sortedKeys = new ArrayList<>(configuredClients.keySet());
            Collections.sort(sortedKeys);
            for (String clientName : sortedKeys) {
                HttpClientItemProperties item = configuredClients.get(clientName);
                HttpProxyProperties proxy = item != null ? item.getProxy() : null;
                HttpSslProperties ssl = item != null ? item.getResolvedSsl() : null;
                boolean proxyEnabled = proxy != null && proxy.isEnabled() && StringUtils.hasText(proxy.getHost());
                boolean sslEnabled = ssl != null && (ssl.hasKeyStore() || ssl.isInsecure());
                items.add(RegistryClientItemVO.builder()
                        .clientName(clientName)
                        .registered(restClientRegistry.contains(clientName))
                        .baseUrl(restClientRegistry.resolveBaseUrl(clientName, null))
                        .propagateContext(item != null && item.isPropagateContext())
                        .retryEnabled(item != null && item.getRetry() != null && item.getRetry().isEnabled())
                        .proxyEnabled(proxyEnabled)
                        .proxyEndpoint(proxyEnabled ? proxy.getHost() + ":" + proxy.getPort() : null)
                        .sslEnabled(sslEnabled)
                        .keyStoreType(ssl != null ? ssl.getKeyStoreType() : null)
                        .build());
            }
        }

        return RegistryInspectVO.builder()
                .registeredCount(restClientRegistry.size())
                .registeredClientNames(registeredNames)
                .clients(items)
                .build();
    }
}
