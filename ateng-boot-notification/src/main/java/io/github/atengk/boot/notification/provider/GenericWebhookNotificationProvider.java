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

import java.util.Map;

/**
 * 通用自定义 JSON Webhook 机器人平台驱动实现
 * 面向开发者自建告警网关或第三方标准化 Webhook 端点，支持标准 JSON 载荷与可选 HMAC-SHA256 请求头签名
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
public class GenericWebhookNotificationProvider extends AbstractNotificationProvider {

    public static final String HEADER_WEBHOOK_SECRET = "X-Webhook-Secret";
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

        // 2. 组装 HTTP 请求体与专属安全签名头
        RestClient.RequestBodySpec requestSpec = restClient.post()
                .uri(targetUrl)
                .contentType(MediaType.APPLICATION_JSON);

        if (channel.getSecret() != null && !channel.getSecret().isBlank()) {
            String secret = channel.getSecret();
            String signature = NotificationSignUtil.signHmacSha256Hex(secret, jsonPayload);
            requestSpec.header(HEADER_WEBHOOK_SECRET, secret);
            if (signature != null) {
                requestSpec.header(HEADER_SIGNATURE_SHA256, signature);
            }
        }

        // 3. 执行 HTTP POST 发送
        ResponseEntity<String> response = requestSpec
                .body(jsonPayload)
                .retrieve()
                .toEntity(String.class);

        return response.getBody();
    }
}
