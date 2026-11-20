package io.github.atengk.boot.notification.provider;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.notification.adapter.GenericPayloadBuilder;
import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.model.NotificationMessage;
import io.github.atengk.boot.notification.model.Platform;
import io.github.atengk.boot.notification.util.NotificationDesensitizeUtil;
import io.github.atengk.boot.notification.util.NotificationSignUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Map;

/**
 * 通用自定义 JSON Webhook 机器人平台驱动实现
 * 面向开发者自建告警网关或第三方标准化 Webhook 端点，支持标准 JSON 载荷与基于时间戳防重放的 HMAC-SHA256 请求头签名
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
public class GenericWebhookNotificationProvider extends AbstractNotificationProvider {

    public static final String HEADER_WEBHOOK_TIMESTAMP = "X-Webhook-Timestamp";
    public static final String HEADER_SIGNATURE_SHA256 = "X-Signature-SHA256";

    public GenericWebhookNotificationProvider() {
        super(Platform.GENERIC);
    }

    @Override
    protected String doSend(NotificationChannel channel, NotificationMessage message,
                            RestClient restClient, String messageId) throws Exception {
        String targetUrl = channel.getWebhook();

        if (log.isDebugEnabled()) {
            log.debug("Sending Generic webhook notification to [{}] (channel={}, messageId={})",
                    NotificationDesensitizeUtil.maskWebhook(targetUrl), channel.getName(), messageId);
        }

        // 1. 转译构建标准化通用 JSON 载荷
        Map<String, Object> payload = GenericPayloadBuilder.INSTANCE.buildPayload(message);
        String jsonPayload = JsonUtil.toJsonString(payload);

        // 2. 组装 HTTP 请求体与专属安全签名头（传入不可变 URI 实例，防止 Query 参数被二次编码）
        RestClient.RequestBodySpec requestSpec = restClient.post()
                .uri(URI.create(targetUrl))
                .contentType(MediaType.APPLICATION_JSON);

        if (channel.getSecret() != null && !channel.getSecret().isBlank()) {
            long timestamp = System.currentTimeMillis();
            String signature = NotificationSignUtil.signGenericWebhook(channel.getSecret(), timestamp, jsonPayload);
            requestSpec.header(HEADER_WEBHOOK_TIMESTAMP, String.valueOf(timestamp));
            if (signature != null) {
                requestSpec.header(HEADER_SIGNATURE_SHA256, signature);
            }
        }

        // 3. 执行 HTTP POST 发送（传入不可变 URI 实例，防止 Query 参数被二次编码）
        ResponseEntity<String> response = requestSpec
                .body(jsonPayload)
                .retrieve()
                .toEntity(String.class);

        return response.getBody();
    }
}
