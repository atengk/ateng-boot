package io.github.atengk.boot.sms.handler;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * 默认短信发送失败日志补偿处理器
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
public class LoggingSmsSendFailureHandler implements SmsSendFailureHandler {

    @Override
    public void handle(SmsRequest request, SmsChannel targetChannel, Exception cause) {
        if (log.isErrorEnabled()) {
            String channelName = targetChannel != null ? targetChannel.getName() : "unknown";
            String phone = request != null ? DesensitizeUtil.mobilePhone(request.getPhone()) : "unknown";
            String errMsg = cause != null ? cause.getMessage() : "Unknown error";
            log.error("[SmsSendFailureHandler] 短信发送链路最终失败补偿触发 -> 渠道: {}, 目标号码: {}, 异常: {}",
                    channelName, phone, errMsg);
        }
    }
}
