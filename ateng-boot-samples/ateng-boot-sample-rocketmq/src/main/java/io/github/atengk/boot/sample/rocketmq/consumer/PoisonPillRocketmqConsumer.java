package io.github.atengk.boot.sample.rocketmq.consumer;

import io.github.atengk.boot.rocketmq.annotation.RocketmqListener;
import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import jakarta.validation.Valid;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 毒丸报文消费者（演示 JSR-303 格式校验失败或不可自愈异常被判定为毒丸并安全丢弃返回 SUCCESS）
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@Component
public class PoisonPillRocketmqConsumer {

    @Getter
    private final AtomicInteger poisonProcessCount = new AtomicInteger(0);

    @RocketmqListener(
            topic = "sample_order_topic",
            consumerGroup = "sample_poison_group",
            tag = "poison"
    )
    public void onPoisonMessage(@Valid OrderMessage order) {
        // 如果数据异常（如 orderId 包含 POISON_FATAL），抛出不可自愈的致命异常
        if (order.getOrderId() != null && order.getOrderId().contains("POISON_FATAL")) {
            throw new IllegalArgumentException("致命数据格式错误，不可自愈，丢弃毒丸: orderId=" + order.getOrderId());
        }

        poisonProcessCount.incrementAndGet();
        log.info("[SAMPLE-POISON] 正常消费: orderId={}", order.getOrderId());
    }

    public void clear() {
        poisonProcessCount.set(0);
    }
}
