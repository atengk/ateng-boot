package io.github.atengk.boot.sample.sms.handler;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.sms.handler.SmsSendFailureHandler;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsRequest;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 示例短信最终投递失败后置补偿处理器
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
@Component
public class SampleSmsFailureHandler implements SmsSendFailureHandler {

    @Getter
    private final AtomicInteger failureCount = new AtomicInteger(0);

    @Getter
    private final CopyOnWriteArrayList<String> failedPhones = new CopyOnWriteArrayList<>();

    @Override
    public void handle(SmsRequest request, SmsChannel targetChannel, Exception cause) {
        failureCount.incrementAndGet();
        if (request != null && request.getPhone() != null) {
            failedPhones.add(request.getPhone());
        }
        log.warn("[SampleSmsFailureHandler] 捕获短信终态失败 -> 渠道: {}, 号码: {}, 模板: {}, 原因: {}",
                targetChannel != null ? targetChannel.getName() : "unknown",
                request != null ? DesensitizeUtil.mobilePhone(request.getPhone()) : "null",
                request != null ? request.getTemplateId() : "null",
                cause != null ? cause.getMessage() : "null");
    }

    public void clear() {
        failureCount.set(0);
        failedPhones.clear();
    }
}
