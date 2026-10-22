package io.github.atengk.boot.sample.kafka.consumer;

import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.kafka.constant.KafkaConstants;
import io.github.atengk.boot.sample.kafka.config.KafkaScenarioTopologyConfig;
import io.github.atengk.boot.sample.kafka.model.OrderMessage;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 消费端幂等防重业务消费者（演示业务幂等防重拦截与 IdempotentException 静默提交）
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@Component
public class IdempotentKafkaConsumer {

    @Getter
    private final Set<String> processedOrderIds = Collections.synchronizedSet(new HashSet<>());

    @Getter
    private final AtomicInteger idempotentHitCount = new AtomicInteger(0);

    @KafkaListener(
            topics = KafkaScenarioTopologyConfig.TOPIC_ORDER,
            groupId = "sample-idempotent-group",
            containerFactory = KafkaConstants.DEFAULT_CONTAINER_FACTORY
    )
    public void onOrderMessage(@Payload OrderMessage order) {
        processOrderWithIdempotentCheck(order);
    }

    public void processOrderWithIdempotentCheck(OrderMessage order) {
        String orderId = order.getOrderId();
        // 模拟 Redis/本地防重锁检测
        if (processedOrderIds.contains(orderId)) {
            idempotentHitCount.incrementAndGet();
            log.warn("[IDEMPOTENT-HIT] 订单已被处理，触发幂等防重拦截: orderId={}", orderId);
            throw new IdempotentException("订单已被成功处理，阻断重复消费: " + orderId);
        }
        processedOrderIds.add(orderId);
        log.info("[IDEMPOTENT-SUCCESS] 首次消费订单成功: orderId={}", orderId);
    }

    public void clear() {
        processedOrderIds.clear();
        idempotentHitCount.set(0);
    }
}
