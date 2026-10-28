package io.github.atengk.boot.sample.rocketmq.consumer;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.rocketmq.annotation.RocketmqListener;
import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import jakarta.validation.Valid;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 订单标准消息监听器（演示 Tag 服务端过滤、微服务推断反序列化与四维上下文透传）
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@Component
public class OrderStandardConsumer {

    @Getter
    private final List<OrderMessage> receivedOrders = new CopyOnWriteArrayList<>();
    @Getter
    private final List<String> receivedTraceIds = new CopyOnWriteArrayList<>();
    @Getter
    private final List<String> receivedTenantIds = new CopyOnWriteArrayList<>();

    @RocketmqListener(
            topic = "sample_order_topic",
            consumerGroup = "sample_order_group",
            tag = "create || pay"
    )
    public void onOrderMessage(@Valid OrderMessage order, MessageView messageView) {
        String traceId = MDC.get("traceId");
        String tenantId = TenantContextHolder.getTenantId();
        String userId = UserContextHolder.getUserId();

        log.info("[SAMPLE-CONSUME] 接收订单消息: orderId={}, tag={}, traceId={}, tenantId={}, userId={}",
                order.getOrderId(),
                messageView.getTag().orElse(""),
                traceId,
                tenantId,
                userId);

        receivedOrders.add(order);
        if (traceId != null) {
            receivedTraceIds.add(traceId);
        }
        if (tenantId != null) {
            receivedTenantIds.add(tenantId);
        }
    }

    public void clear() {
        receivedOrders.clear();
        receivedTraceIds.clear();
        receivedTenantIds.clear();
    }
}
