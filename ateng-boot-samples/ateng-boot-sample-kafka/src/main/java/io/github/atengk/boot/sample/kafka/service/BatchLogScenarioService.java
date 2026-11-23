package io.github.atengk.boot.sample.kafka.service;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.kafka.executor.KafkaRecordExecutor;
import io.github.atengk.boot.kafka.template.KafkaReliableTemplate;
import io.github.atengk.boot.sample.kafka.config.KafkaScenarioTopologyConfig;
import io.github.atengk.boot.sample.kafka.consumer.BatchRecordConsumer;
import io.github.atengk.boot.sample.kafka.model.OrderMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 批量日志与多租户隔离处理场景服务
 *
 * <p>演示批量消息投递、基于 {@link KafkaRecordExecutor} 实施每条记录的租户上下文激活与现场自恢复，
 * 以及逐条容错执行防单点故障阻断整批处理。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchLogScenarioService {

    private final KafkaReliableTemplate kafkaReliableTemplate;
    private final BatchRecordConsumer batchRecordConsumer;

    private final List<FailedRecordItem> failedItems = Collections.synchronizedList(new ArrayList<>());

    /**
     * 批量可靠发送日志消息
     */
    public List<SendResult<Object, Object>> sendBatchLogs(List<OrderMessage> orders) {
        return kafkaReliableTemplate.sendBatch(KafkaScenarioTopologyConfig.TOPIC_BATCH, orders);
    }

    /**
     * 安全处理批量消息批次，每条记录多租户独立隔离，单条失败回调补偿，不阻断剩余记录
     */
    public void processBatchSafely(List<ConsumerRecord<String, OrderMessage>> records) {
        KafkaRecordExecutor.processBatchSafely(
                records,
                (record, payload) -> {
                    String currentTenant = TenantContextHolder.getTenantId();
                    log.info("[BATCH-LOG-SERVICE] 消费记录成功: key={}, tenant={}, offset={}",
                            record.key(), currentTenant, record.offset());
                    if ("ORD-CORRUPTED".equals(record.key())) {
                        throw new IllegalArgumentException("模拟单条记录处理异常: " + record.key());
                    }
                },
                (record, error) -> {
                    log.warn("[BATCH-LOG-SERVICE] 单条记录消费失败补偿: key={}, error={}", record.key(), error.getMessage());
                    failedItems.add(new FailedRecordItem(record.key(), record.offset(), error.getMessage()));
                }
        );
    }

    public List<BatchRecordConsumer.BatchItemResult> getConsumerProcessedItems() {
        return batchRecordConsumer.getProcessedItems();
    }

    public List<FailedRecordItem> getFailedItems() {
        return failedItems;
    }

    public void clear() {
        batchRecordConsumer.clear();
        failedItems.clear();
    }

    public record FailedRecordItem(
            String key,
            long offset,
            String errorMessage
    ) {}
}
