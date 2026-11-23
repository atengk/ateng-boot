package io.github.atengk.boot.sample.rocketmq.consumer;

import io.github.atengk.boot.rocketmq.spi.RocketmqPoisonPillCallback;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Sample 消费毒丸回调记录器
 *
 * <p>演示业务侧接入 {@link RocketmqPoisonPillCallback} SPI 捕获畸形毒丸与致命异常事件，
 * 并供 E2E 与 Web 测试断言审计回调触发状态。</p>
 *
 * @author Ateng
 * @since 2026-11-15
 */
@Slf4j
@Component
public class SamplePoisonPillCallbackRecorder implements RocketmqPoisonPillCallback {

    private final List<PoisonPillEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void onPoisonPill(MessageView messageView,
                             String topic,
                             String consumerGroup,
                             String dlqTopic,
                             boolean forwardedToDlq,
                             Throwable cause) {
        String messageId = messageView != null && messageView.getMessageId() != null
                ? messageView.getMessageId().toString()
                : null;
        String exceptionClass = cause != null ? cause.getClass().getName() : null;
        String exceptionMessage = cause != null ? cause.getMessage() : null;
        events.add(new PoisonPillEvent(
                messageId,
                topic,
                consumerGroup,
                dlqTopic,
                forwardedToDlq,
                exceptionClass,
                exceptionMessage));
        log.warn("[SAMPLE-POISON-CALLBACK] 捕获毒丸事件: msgId={}, topic={}, group={}, dlqTopic={}, forwarded={}, cause={}",
                messageId, topic, consumerGroup, dlqTopic, forwardedToDlq, exceptionClass);
    }

    /**
     * 获取已捕获的毒丸回调事件只读快照
     *
     * @return 不可变事件列表
     */
    public List<PoisonPillEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    /**
     * 清空记录的回调事件
     */
    public void clear() {
        events.clear();
    }

    /**
     * 毒丸回调审计快照记录
     */
    public record PoisonPillEvent(
            String messageId,
            String topic,
            String consumerGroup,
            String dlqTopic,
            boolean forwardedToDlq,
            String exceptionClass,
            String exceptionMessage
    ) {
    }
}
