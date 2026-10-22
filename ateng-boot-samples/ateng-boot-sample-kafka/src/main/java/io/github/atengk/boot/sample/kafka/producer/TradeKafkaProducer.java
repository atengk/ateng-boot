package io.github.atengk.boot.sample.kafka.producer;

import io.github.atengk.boot.kafka.template.KafkaReliableTemplate;
import io.github.atengk.boot.sample.kafka.config.KafkaScenarioTopologyConfig;
import io.github.atengk.boot.sample.kafka.model.OrderMessage;
import io.github.atengk.boot.sample.kafka.model.UserEventMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 生产端交易与事件消息发送器
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradeKafkaProducer {

    private final KafkaReliableTemplate kafkaReliableTemplate;

    public SendResult<Object, Object> sendSyncOrder(OrderMessage order) {
        return kafkaReliableTemplate.sendReliable(KafkaScenarioTopologyConfig.TOPIC_ORDER, order.getOrderId(), order);
    }

    public CompletableFuture<SendResult<Object, Object>> sendAsyncUserEvent(UserEventMessage event) {
        return kafkaReliableTemplate.sendReliableAsync(KafkaScenarioTopologyConfig.TOPIC_USER, event.getUserId(), event);
    }

    public SendResult<Object, Object> sendPartitionDirected(int partition, String key, Object payload) {
        return kafkaReliableTemplate.sendReliable(KafkaScenarioTopologyConfig.TOPIC_ORDER, partition, key, payload);
    }

    public List<SendResult<Object, Object>> sendBatchOrders(List<OrderMessage> orders) {
        return kafkaReliableTemplate.sendBatch(KafkaScenarioTopologyConfig.TOPIC_BATCH, orders);
    }

    public SendResult<Object, Object> sendLargePayload(byte[] payload) {
        return kafkaReliableTemplate.sendReliable(KafkaScenarioTopologyConfig.TOPIC_ORDER, "large-key", payload);
    }

    public SendResult<Object, Object> sendPoisonPill(String poisonData) {
        return kafkaReliableTemplate.sendReliable(KafkaScenarioTopologyConfig.TOPIC_POISON, "poison-key", poisonData);
    }
}
