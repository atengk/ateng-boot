package io.github.atengk.boot.sms.handler;

import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsRequest;

/**
 * 短信最终发送失败补偿扩展点 SPI
 * 在主渠道与所有 Failover 容灾备用渠道均投递失败时触发后置补偿（如落库补偿重试表、发送告警消息等）
 *
 * @author Ateng
 * @since 2026-11-02
 */
public interface SmsSendFailureHandler {

    /**
     * 最终失败后置补偿回调
     *
     * @param request       原始发信请求对象
     * @param targetChannel 最终尝试的渠道配置
     * @param cause         失败根因异常
     */
    void handle(SmsRequest request, SmsChannel targetChannel, Exception cause);
}
