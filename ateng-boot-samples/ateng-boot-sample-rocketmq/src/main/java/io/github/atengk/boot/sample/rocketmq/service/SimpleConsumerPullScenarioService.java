package io.github.atengk.boot.sample.rocketmq.service;

import io.github.atengk.boot.rocketmq.template.RocketmqSimpleConsumerTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * 消费端 SimpleConsumer 流式拉取与租约控制场景业务服务
 *
 * <p>演示微批长轮询流式拉取、隐身不可见租约续期、显式 ACK 确认以及高阶函数式消费闭包。</p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimpleConsumerPullScenarioService {

    private final RocketmqSimpleConsumerTemplate simpleConsumerTemplate;

    public List<MessageView> pullMessages(String topic, String group, int maxNum, Duration invisibleDuration) {
        log.info("[SIMPLE-PULL-SCENARIO] 流式批量拉取消息: topic={}, group={}, maxNum={}, invisibleDuration={}",
                topic, group, maxNum, invisibleDuration);
        return simpleConsumerTemplate.receive(topic, group, maxNum, invisibleDuration);
    }

    public void renewLease(String group, MessageView messageView, Duration newDuration) {
        log.info("[SIMPLE-PULL-SCENARIO] 动态续期不可见租约: group={}, messageId={}, newDuration={}",
                group, messageView.getMessageId(), newDuration);
        simpleConsumerTemplate.changeInvisibleDuration(group, messageView, newDuration);
    }

    public void acknowledge(String group, MessageView messageView) {
        log.info("[SIMPLE-PULL-SCENARIO] 显式确认已消费消息: group={}, messageId={}", group, messageView.getMessageId());
        simpleConsumerTemplate.ack(group, messageView);
    }

    public <T> int pullAndExecute(String topic, String group, Class<T> payloadClass, Consumer<T> consumer) {
        log.info("[SIMPLE-PULL-SCENARIO] 执行高阶推断反序列化消费闭包: topic={}, group={}", topic, group);
        return simpleConsumerTemplate.receiveAndExecute(topic, group, payloadClass, consumer);
    }
}
