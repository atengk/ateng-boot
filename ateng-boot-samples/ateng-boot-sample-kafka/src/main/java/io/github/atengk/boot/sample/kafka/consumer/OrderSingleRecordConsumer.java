package io.github.atengk.boot.sample.kafka.consumer;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.kafka.constant.KafkaConstants;
import io.github.atengk.boot.sample.kafka.config.KafkaScenarioTopologyConfig;
import io.github.atengk.boot.sample.kafka.model.OrderMessage;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 订单单条记录消费监听器（演示单条推断反序列化、四维上下文透明还原与 bizKey 自动提取）
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@Component
public class OrderSingleRecordConsumer {

    @Getter
    private final List<ConsumedRecord> consumedRecords = Collections.synchronizedList(new ArrayList<>());

    @KafkaListener(
            topics = KafkaScenarioTopologyConfig.TOPIC_ORDER,
            containerFactory = KafkaConstants.DEFAULT_CONTAINER_FACTORY
    )
    public void onOrderMessage(@Payload OrderMessage order,
                               @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
                               @Header(name = KafkaHeaders.RECEIVED_PARTITION, required = false) Integer partition,
                               @Header(name = KafkaHeaders.OFFSET, required = false) Long offset) {
        String traceId = MDC.get("traceId");
        String tenantId = TenantContextHolder.getTenantId();
        String userId = UserContextHolder.getUserId();

        log.info("[SAMPLE-CONSUME-ORDER] 消费订单成功: key={}, partition={}, offset={}, orderId={}, amount={}, traceId={}, tenantId={}, userId={}",
                key, partition, offset, order.getOrderId(), order.getAmount(), traceId, tenantId, userId);

        consumedRecords.add(new ConsumedRecord(key, partition, offset, order, traceId, tenantId, userId));
    }

    public void clear() {
        consumedRecords.clear();
    }

    public record ConsumedRecord(
            String key,
            Integer partition,
            Long offset,
            OrderMessage order,
            String traceId,
            String tenantId,
            String userId
    ) {}
}
