package io.github.atengk.boot.sample.kafka.consumer;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.kafka.constant.KafkaConstants;
import io.github.atengk.boot.kafka.executor.KafkaRecordExecutor;
import io.github.atengk.boot.sample.kafka.config.KafkaScenarioTopologyConfig;
import io.github.atengk.boot.sample.kafka.model.OrderMessage;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 批量订单消费监听器（演示 BATCH_CONTAINER_FACTORY 批量拉取与 KafkaRecordExecutor 多租户上下文安全隔离）
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@Component
public class BatchRecordConsumer {

    @Getter
    private final List<BatchItemResult> processedItems = Collections.synchronizedList(new ArrayList<>());

    @KafkaListener(
            topics = KafkaScenarioTopologyConfig.TOPIC_BATCH,
            containerFactory = KafkaConstants.BATCH_CONTAINER_FACTORY
    )
    public void onBatchRecords(List<ConsumerRecord<String, OrderMessage>> records) {
        log.info("[SAMPLE-CONSUME-BATCH] 收到批量拉取消息批次: size={}", records.size());

        // 使用 KafkaRecordExecutor 实施每条记录的租户上下文隔离与异常熔断隔离
        KafkaRecordExecutor.processBatchSafely(records, (record, payload) -> {
            String currentTenant = TenantContextHolder.getTenantId();
            log.info("[BATCH-RECORD-ITEM] 处理单条记录: key={}, offset={}, currentTenant={}, payloadTenant={}",
                    record.key(), record.offset(), currentTenant, payload != null ? payload.getTenantId() : "null");

            processedItems.add(new BatchItemResult(record.key(), record.offset(), payload, currentTenant));
        });
    }

    public void clear() {
        processedItems.clear();
    }

    public record BatchItemResult(
            String key,
            long offset,
            OrderMessage order,
            String tenantId
    ) {}
}
