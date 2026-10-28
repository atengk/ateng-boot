package io.github.atengk.boot.sample.rabbitmq.consumer;

import io.github.atengk.boot.sample.rabbitmq.config.RabbitScenarioTopologyConfig;
import io.github.atengk.boot.sample.rabbitmq.model.OrderCreateMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 订单创建消费者 (场景 A: Direct 路由、Quorum 队列与泛型推断消费实测)
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
@Component
public class OrderConsumer {

    private final List<OrderCreateMessage> receivedMessages = new CopyOnWriteArrayList<>();

    @RabbitListener(queues = RabbitScenarioTopologyConfig.QUEUE_ORDER_CREATE)
    public void onOrderCreate(OrderCreateMessage message,
                              @Header(name = "X-Trace-Id", required = false) String traceId,
                              @Header(name = "X-Tenant-Id", required = false) String tenantId) {
        log.info("[CONSUMER-ORDER] 消费订单创建消息成功: orderId={}, amount={}, traceId={}, tenantId={}",
                message.orderId(), message.amount(), traceId, tenantId);
        receivedMessages.add(message);
    }

    public List<OrderCreateMessage> getReceivedMessages() {
        return receivedMessages;
    }

    public void clear() {
        receivedMessages.clear();
    }
}
