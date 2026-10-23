package io.github.atengk.boot.sample.notification.handler;

import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.handler.NotificationSendFailureHandler;
import io.github.atengk.boot.notification.model.NotificationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 示例：自定义发信失败降级补偿处理器 SPI 实现
 * 演示接管默认处理器，当事务提交后发信或异步发信失败时执行结构化告警或落库补偿
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
@Component
public class SampleNotificationSendFailureHandler implements NotificationSendFailureHandler {

    @Override
    public void onFailure(NotificationChannel channel, NotificationMessage message, Throwable throwable) {
        String channelName = channel != null ? channel.getName() : "unknown";
        String msgType = message != null ? message.getMessageType().name() : "unknown";
        log.warn("SampleNotificationSendFailureHandler: 捕获通知发信失败补偿事件 [channel={}, msgType={}, error={}]",
                channelName, msgType, throwable != null ? throwable.getMessage() : "none");
        // 生产实践中可写入死信表、发起重试调度或调用备用通道
    }
}
