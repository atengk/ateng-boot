package io.github.atengk.boot.kafka.autoconfigure;

import io.github.atengk.boot.kafka.properties.AtengKafkaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 生产端高可靠与高吞吐默认参数定制器
 *
 * <p>为 Kafka 生产端开箱预设生产级基线配置（acks=all, 幂等, 32KB微批, 10ms linger, snappy 压缩等）。
 * 若业务在配置文件（{@code spring.kafka.producer.*}）中显式定义了配置，则业务配置具有最高优先级，保持绝对优先。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@RequiredArgsConstructor
public class AtengKafkaProducerFactoryCustomizer implements DefaultKafkaProducerFactoryCustomizer {

    private final AtengKafkaProperties properties;

    @Override
    public void customize(DefaultKafkaProducerFactory<?, ?> producerFactory) {
        if (!properties.getProducer().isDefaultTuningEnabled()) {
            log.debug("[ateng-boot-kafka] 生产端默认调优已由配置显式禁用 (defaultTuningEnabled=false)");
            return;
        }

        Map<String, Object> existingConfigs = producerFactory.getConfigurationProperties();
        Map<String, Object> updates = new HashMap<>();

        // 1. 零丢信与幂等生产
        applyIfAbsent(existingConfigs, updates, ProducerConfig.ACKS_CONFIG, "all");
        applyIfAbsent(existingConfigs, updates, ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        applyIfAbsent(existingConfigs, updates, ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        applyIfAbsent(existingConfigs, updates, ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);

        // 2. 高吞吐微批聚合与压缩
        applyIfAbsent(existingConfigs, updates, ProducerConfig.BATCH_SIZE_CONFIG, 32768); // 32KB
        applyIfAbsent(existingConfigs, updates, ProducerConfig.LINGER_MS_CONFIG, 10); // 10ms
        applyIfAbsent(existingConfigs, updates, ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");

        // 3. 缓冲容量与超时保护
        applyIfAbsent(existingConfigs, updates, ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432L); // 32MB
        applyIfAbsent(existingConfigs, updates, ProducerConfig.MAX_BLOCK_MS_CONFIG, 60000L); // 60s

        if (!updates.isEmpty()) {
            producerFactory.updateConfigs(updates);
            log.info("[ateng-boot-kafka] 生产端已应用高吞吐微批调优参数: acks=all, idempotence=true, batch.size=32KB, linger.ms=10ms, compression=snappy");
        }
    }

    private void applyIfAbsent(Map<String, Object> existing, Map<String, Object> updates, String key, Object defaultValue) {
        if (!existing.containsKey(key)) {
            updates.put(key, defaultValue);
        }
    }
}
