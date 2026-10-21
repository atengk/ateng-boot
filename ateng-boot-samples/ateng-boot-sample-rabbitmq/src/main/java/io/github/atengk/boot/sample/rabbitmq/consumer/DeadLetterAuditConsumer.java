package io.github.atengk.boot.sample.rabbitmq.consumer;

import io.github.atengk.boot.rabbitmq.helper.RabbitDeadLetterHelper;
import io.github.atengk.boot.sample.rabbitmq.config.RabbitScenarioTopologyConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 死信队列审计消费者 (场景 D: 结合 RabbitDeadLetterHelper 提取诊断信息)
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
@Component
public class DeadLetterAuditConsumer {

    private final List<RabbitDeadLetterHelper.DeadLetterRecord> auditRecords = new CopyOnWriteArrayList<>();

    @RabbitListener(queues = RabbitScenarioTopologyConfig.QUEUE_BIZ_DLQ)
    public void onDeadLetter(Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        String originQueue = RabbitDeadLetterHelper.getOriginalQueue(message);
        String originExchange = RabbitDeadLetterHelper.getOriginalExchange(message);
        String reason = RabbitDeadLetterHelper.getDeathReason(message);
        long count = RabbitDeadLetterHelper.getDeathCount(message);
        RabbitDeadLetterHelper.DeadLetterRecord record = RabbitDeadLetterHelper.getFirstDeath(message);

        log.warn("[DLQ-AUDIT] 收到死信消息: body={}, originQueue={}, originExchange={}, reason={}, count={}",
                body, originQueue, originExchange, reason, count);

        if (record != null) {
            auditRecords.add(record);
        }
    }

    public List<RabbitDeadLetterHelper.DeadLetterRecord> getAuditRecords() {
        return auditRecords;
    }

    public void clear() {
        auditRecords.clear();
    }
}
