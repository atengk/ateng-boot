package io.github.atengk.boot.notification.provider;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.notification.adapter.FeishuPayloadBuilder;
import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import io.github.atengk.boot.notification.exception.NotificationSendException;
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
 * 飞书群机器人平台驱动实现
 * 内置自动计算请求体 HMAC-SHA256 签名（timestamp + sign）、Interactive v2 卡片构建及正文 @ 标签注入
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
public class FeishuNotificationProvider extends AbstractNotificationProvider {

    public FeishuNotificationProvider() {
        super(Platform.FEISHU);
    }

    @Override
    protected String doSend(NotificationChannel channel, NotificationMessage message,
                            RestClient restClient, String messageId) throws Exception {
        String targetUrl = channel.getWebhook();

        if (log.isDebugEnabled()) {
            log.debug("Sending Feishu notification to [{}] (channel={}, messageId={})",
                    NotificationDesensitizeUtil.maskWebhook(targetUrl), channel.getName(), messageId);
        }

        // 1. 转译构建飞书专有 Payload
        Map<String, Object> payload = FeishuPayloadBuilder.INSTANCE.buildPayload(message);

        // 2. 若配置了加签密钥，自动计算 10 位秒级时间戳与签名并合并注入请求体顶层
        if (channel.getSecret() != null && !channel.getSecret().isBlank()) {
            long timestamp = System.currentTimeMillis() / 1000;
            String sign = NotificationSignUtil.signFeishu(channel.getSecret(), timestamp);
            payload.put("timestamp", String.valueOf(timestamp));
            payload.put("sign", sign);
        }

        // 3. 执行 HTTP POST 发送
        ResponseEntity<String> response = restClient.post()
                .uri(targetUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toEntity(String.class);

        String body = response.getBody();

        // 4. 解析并校验平台响应
        if (body != null && !body.isBlank()) {
            JsonNode root = JsonUtil.getObjectMapper().readTree(body);
            int code = root.has("code") ? root.path("code").asInt(0) : root.path("StatusCode").asInt(0);
            String msg = root.has("msg") ? root.path("msg").asText("ok") : root.path("StatusMessage").asText("ok");
            if (code != 0) {
                NotificationResultCode resultCode = determineFeishuResultCode(code);
                throw new NotificationSendException(channel.getName(), channel.getPlatform().name(), body, resultCode,
                        "飞书平台发信失败 [code=" + code + "]: " + msg);
            }
        }

        return body;
    }

    private NotificationResultCode determineFeishuResultCode(int code) {
        if (code == 99991400) {
            return NotificationResultCode.NOTIFICATION_RATE_LIMIT_EXCEEDED;
        }
        if (code == 19001 || code == 19002 || code == 99991663 || code == 99991668) {
            return NotificationResultCode.NOTIFICATION_AUTHENTICATION_ERROR;
        }
        return NotificationResultCode.NOTIFICATION_SEND_ERROR;
    }
}
