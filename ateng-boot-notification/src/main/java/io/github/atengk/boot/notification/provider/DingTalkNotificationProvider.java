package io.github.atengk.boot.notification.provider;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.notification.adapter.DingTalkPayloadBuilder;
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
 * 钉钉群机器人平台驱动实现
 * 内置自动计算 HMAC-SHA256 URL 签名参数、ActionCard 自适应转译及出站日志审计脱敏
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
public class DingTalkNotificationProvider extends AbstractNotificationProvider {

    public DingTalkNotificationProvider() {
        super(Platform.DINGTALK);
    }

    @Override
    protected String doSend(NotificationChannel channel, NotificationMessage message,
                            RestClient restClient, String messageId) throws Exception {
        String webhook = channel.getWebhook();
        String targetUrl = webhook;

        // 1. 若配置了加签密钥，自动计算 timestamp 与 sign 并追加至 URL Query 参数
        if (channel.getSecret() != null && !channel.getSecret().isBlank()) {
            long timestamp = System.currentTimeMillis();
            String sign = NotificationSignUtil.signDingTalk(channel.getSecret(), timestamp);
            targetUrl = webhook + (webhook.contains("?") ? "&" : "?") + "timestamp=" + timestamp + "&sign=" + sign;
        }

        if (log.isDebugEnabled()) {
            log.debug("Sending DingTalk notification to [{}] (channel={}, messageId={})",
                    NotificationDesensitizeUtil.maskWebhook(targetUrl), channel.getName(), messageId);
        }

        // 2. 转译构建钉钉专有 Payload
        Map<String, Object> payload = DingTalkPayloadBuilder.INSTANCE.buildPayload(message);

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
            int errcode = root.path("errcode").asInt(0);
            String errmsg = root.path("errmsg").asText("ok");
            if (errcode != 0) {
                NotificationResultCode resultCode = determineDingTalkResultCode(errcode);
                throw new NotificationSendException(channel.getName(), channel.getPlatform().name(), body, resultCode,
                        "钉钉平台发信失败 [errcode=" + errcode + "]: " + errmsg);
            }
        }

        return body;
    }

    private NotificationResultCode determineDingTalkResultCode(int errcode) {
        if (errcode == 130101) {
            return NotificationResultCode.NOTIFICATION_RATE_LIMIT_EXCEEDED;
        }
        if (errcode == 300001 || errcode == 310000 || errcode == 88) {
            return NotificationResultCode.NOTIFICATION_AUTHENTICATION_ERROR;
        }
        return NotificationResultCode.NOTIFICATION_SEND_ERROR;
    }
}
