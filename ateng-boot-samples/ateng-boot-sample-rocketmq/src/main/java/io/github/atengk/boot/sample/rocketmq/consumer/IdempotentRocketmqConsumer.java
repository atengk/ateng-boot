package io.github.atengk.boot.sample.rocketmq.consumer;

import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.rocketmq.annotation.RocketmqListener;
import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 防重幂等消费者（演示抛出 IdempotentException 命中防重并静默确认返回 SUCCESS）
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@Component
public class IdempotentRocketmqConsumer {

    private final Set<String> processedOrderIds = ConcurrentHashMap.newKeySet();
    @Getter
    private final AtomicInteger duplicateInterceptCount = new AtomicInteger(0);
    @Getter
    private final AtomicInteger successProcessCount = new AtomicInteger(0);

    @RocketmqListener(
            topic = "sample_order_topic",
            consumerGroup = "sample_idempotent_group",
            tag = "idempotent"
    )
    public void onIdempotentOrder(OrderMessage order) {
        if (!processedOrderIds.add(order.getOrderId())) {
            duplicateInterceptCount.incrementAndGet();
            log.warn("[SAMPLE-IDEMPOTENT] 检测到订单已处理，抛出 IdempotentException 触发静默确认: orderId={}", order.getOrderId());
            throw new IdempotentException("订单已被消费处理，防重幂等生效: orderId=" + order.getOrderId());
        }

        successProcessCount.incrementAndGet();
        log.info("[SAMPLE-IDEMPOTENT] 首次消费处理成功: orderId={}", order.getOrderId());
    }

    public void clear() {
        processedOrderIds.clear();
        duplicateInterceptCount.set(0);
        successProcessCount.set(0);
    }
}
