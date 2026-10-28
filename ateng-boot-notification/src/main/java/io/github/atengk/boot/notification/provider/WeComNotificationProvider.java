package io.github.atengk.boot.notification.provider;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.notification.adapter.WeComPayloadBuilder;
import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import io.github.atengk.boot.notification.exception.NotificationSendException;
import io.github.atengk.boot.notification.model.NotificationMessage;
import io.github.atengk.boot.notification.model.Platform;
import io.github.atengk.boot.notification.util.NotificationDesensitizeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 企业微信群机器人平台驱动实现
 * 内置 Markdown 方言防崩清洗、图文/文本通知卡片转译、图片 MD5 组装与 Webhook Key 路由
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
public class WeComNotificationProvider extends AbstractNotificationProvider {

    public WeComNotificationProvider() {
        super(Platform.WECOM);
    }

    @Override
    protected String doSend(NotificationChannel channel, NotificationMessage message,
                            RestClient restClient, String messageId) throws Exception {
        String targetUrl = channel.getWebhook();

        if (log.isDebugEnabled()) {
            log.debug("Sending WeCom notification to [{}] (channel={}, messageId={})",
                    NotificationDesensitizeUtil.maskWebhook(targetUrl), channel.getName(), messageId);
        }

        // 1. 转译构建企业微信专有 Payload（包含自动 Markdown 表格降级与图片计算）
        Map<String, Object> payload = WeComPayloadBuilder.INSTANCE.buildPayload(message);

        // 2. 执行 HTTP POST 发送
        ResponseEntity<String> response = restClient.post()
                .uri(targetUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toEntity(String.class);

        String body = response.getBody();

        // 3. 解析并校验平台响应
        if (body != null && !body.isBlank()) {
            JsonNode root = JsonUtil.getObjectMapper().readTree(body);
            int errcode = root.path("errcode").asInt(0);
            String errmsg = root.path("errmsg").asText("ok");
            if (errcode != 0) {
                NotificationResultCode resultCode = determineWeComResultCode(errcode);
                throw new NotificationSendException(channel.getName(), channel.getPlatform().name(), body, resultCode,
                        "企业微信平台发信失败 [errcode=" + errcode + "]: " + errmsg);
            }
        }

        return body;
    }

    private NotificationResultCode determineWeComResultCode(int errcode) {
        if (errcode == 45009) {
            return NotificationResultCode.NOTIFICATION_RATE_LIMIT_EXCEEDED;
        }
        if (errcode == 93000 || errcode == 40014 || errcode == 40058) {
            return NotificationResultCode.NOTIFICATION_AUTHENTICATION_ERROR;
        }
        return NotificationResultCode.NOTIFICATION_SEND_ERROR;
    }
}
