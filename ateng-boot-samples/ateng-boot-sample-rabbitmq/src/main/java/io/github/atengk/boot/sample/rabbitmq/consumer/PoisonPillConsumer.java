package io.github.atengk.boot.sample.rabbitmq.consumer;

import io.github.atengk.boot.sample.rabbitmq.config.RabbitScenarioTopologyConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 业务队列消费者 (场景 D: 模拟毒丸与业务异常重试后进入 DLQ)
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
@Component
public class PoisonPillConsumer {

    private final AtomicInteger attemptCount = new AtomicInteger(0);

    @RabbitListener(queues = RabbitScenarioTopologyConfig.QUEUE_BIZ_MAIN)
    public void onBizMessage(String payload) {
        attemptCount.incrementAndGet();
        if ("poison-pill".equals(payload)) {
            log.error("[CONSUMER-BIZ] 收到致命毒丸消息，抛出非法参数异常: {}", payload);
            throw new IllegalArgumentException("毒丸格式严重错误，不可自愈: " + payload);
        } else if ("biz-error".equals(payload)) {
            log.error("[CONSUMER-BIZ] 收到业务重试消息，模拟服务故障: attempt={}", attemptCount.get());
            throw new RuntimeException("下游服务网络抖动");
        }
        log.info("[CONSUMER-BIZ] 业务消息消费成功: {}", payload);
    }

    public int getAttemptCount() {
        return attemptCount.get();
    }

    public void clear() {
        attemptCount.set(0);
    }
}
