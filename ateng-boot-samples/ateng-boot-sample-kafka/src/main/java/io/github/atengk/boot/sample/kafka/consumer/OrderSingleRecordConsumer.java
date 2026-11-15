package io.github.atengk.boot.sample.kafka.consumer;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
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
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                               @Header(name = KafkaHeaders.OFFSET, required = false) Long offset,
                               @Headers Map<String, Object> rawHeaders) {
        String traceId = MDC.get("traceId");
        String tenantId = TenantContextHolder.getTenantId();
        String userId = UserContextHolder.getUserId();
        Map<String, String> customHeaders = extractCustomHeaders(rawHeaders);

        log.info("[SAMPLE-CONSUME-ORDER] 消费订单成功: key={}, partition={}, offset={}, orderId={}, amount={}, traceId={}, tenantId={}, userId={}, customHeaders={}",
                key, partition, offset, order.getOrderId(), order.getAmount(), traceId, tenantId, userId, customHeaders);

        consumedRecords.add(new ConsumedRecord(key, partition, offset, order, traceId, tenantId, userId, customHeaders));
    }

    private Map<String, String> extractCustomHeaders(Map<String, Object> rawHeaders) {
        if (rawHeaders == null || rawHeaders.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawHeaders.entrySet()) {
            String headerKey = entry.getKey();
            if (headerKey == null || headerKey.startsWith("kafka_")
                    || headerKey.equalsIgnoreCase(MessageHeaderConstants.TRACE_ID)
                    || headerKey.equalsIgnoreCase(MessageHeaderConstants.TENANT_ID)
                    || headerKey.equalsIgnoreCase(MessageHeaderConstants.USER_ID)
                    || headerKey.equalsIgnoreCase(MessageHeaderConstants.IGNORE_TENANT)
                    || headerKey.equalsIgnoreCase(MessageHeaderConstants.SEND_TIMESTAMP)
                    || headerKey.equalsIgnoreCase(MessageHeaderConstants.BIZ_KEY)) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof byte[] bytes) {
                result.put(headerKey, new String(bytes, StandardCharsets.UTF_8));
            } else if (value instanceof String str) {
                result.put(headerKey, str);
            }
        }
        return result.isEmpty() ? Map.of() : Collections.unmodifiableMap(result);
    }

    /**
     * 获取当前已消费订单记录的线程安全只读快照
     */
    public List<ConsumedRecord> getConsumedRecordsSnapshot() {
        synchronized (consumedRecords) {
            return List.copyOf(consumedRecords);
        }
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
            String userId,
            Map<String, String> customHeaders
    ) {}
}
