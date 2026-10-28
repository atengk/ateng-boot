package io.github.atengk.boot.sample.rabbitmq.consumer;

import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.sample.rabbitmq.config.RabbitScenarioTopologyConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 消费端防重幂等消费者 (场景 B: 抛出 IdempotentException 验证静默确认 Silent ACK)
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
@Component
public class IdempotentOrderConsumer {

    private final Set<String> processedKeys = ConcurrentHashMap.newKeySet();
    private final AtomicInteger normalProcessedCount = new AtomicInteger(0);
    private final AtomicInteger idempotentTriggeredCount = new AtomicInteger(0);

    @RabbitListener(queues = RabbitScenarioTopologyConfig.QUEUE_ORDER_IDEMPOTENT)
    public void onConsume(String payload,
                          @Header(name = "X-Biz-Key", required = false) String bizKey) {
        String key = bizKey != null ? bizKey : payload;
        // 模拟幂等去重检查：若已处理，抛出 IdempotentException 触发全局切面静默 ACK
        if (!processedKeys.add(key)) {
            idempotentTriggeredCount.incrementAndGet();
            log.warn("[CONSUMER-IDEMPOTENT] 检测到重复消息，抛出 IdempotentException 触发静默确认: bizKey={}", key);
            throw new IdempotentException("订单消息已处理，幂等拦截静默确认: " + key);
        }

        normalProcessedCount.incrementAndGet();
        log.info("[CONSUMER-IDEMPOTENT] 首次消费处理成功: bizKey={}, payload={}", key, payload);
    }

    public int getNormalProcessedCount() {
        return normalProcessedCount.get();
    }

    public int getIdempotentTriggeredCount() {
        return idempotentTriggeredCount.get();
    }

    public void clear() {
        processedKeys.clear();
        normalProcessedCount.set(0);
        idempotentTriggeredCount.set(0);
    }
}
