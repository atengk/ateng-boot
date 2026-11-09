package io.github.atengk.boot.sample.http.service;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.http.options.RequestOptions;
import io.github.atengk.boot.http.template.HttpTemplate;
import io.github.atengk.boot.sample.http.client.WechatPayClient;
import io.github.atengk.boot.sample.http.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 外部支付与治理防御业务场景服务
 * 演示向外部非信任公网发起请求时安全剥离敏感头、瞬态 503 指数退避重试与出站 cURL 脱敏日志
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
        boolean mdcInjected = false;
        String currentTraceId = MDC.get("traceId");
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
            TenantContextHolder.clear();
            UserContextHolder.clear();
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
}
