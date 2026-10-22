package io.github.atengk.boot.sample.kafka.config;

import io.github.atengk.boot.kafka.builder.KafkaTopicBuilder;
import io.github.atengk.boot.kafka.spi.KafkaTransactionFailureCallback;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Kafka 场景样例拓扑声明与配置
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@Configuration
public class KafkaScenarioTopologyConfig {

    public static final String TOPIC_ORDER = "sample.order.topic";
    public static final String TOPIC_USER = "sample.user.topic";
    public static final String TOPIC_BATCH = "sample.batch.topic";
    public static final String TOPIC_POISON = "sample.poison.topic";

    @Bean
    public KafkaAdmin.NewTopics orderTopics() {
        return KafkaTopicBuilder.topic(TOPIC_ORDER)
                .partitions(3)
                .replicas(1)
                .minInSyncReplicas(1)
                .retention(Duration.ofDays(7))
                .withDlt()
                .build();
    }

    @Bean
    public KafkaAdmin.NewTopics userTopics() {
        return KafkaTopicBuilder.topic(TOPIC_USER)
                .partitions(2)
                .replicas(1)
                .withDlt()
                .build();
    }

    @Bean
    public KafkaAdmin.NewTopics batchTopics() {
        return KafkaTopicBuilder.topic(TOPIC_BATCH)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public KafkaAdmin.NewTopics poisonTopics() {
        return KafkaTopicBuilder.topic(TOPIC_POISON)
                .partitions(1)
                .replicas(1)
                .withDlt()
                .build();
    }

    @Bean
    public SampleTxFailureCallback sampleTxFailureCallback() {
        return new SampleTxFailureCallback();
    }

    @Getter
    public static class SampleTxFailureCallback implements KafkaTransactionFailureCallback {
        private final List<String> failedRecords = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onFailure(String topic, Integer partition, String key, Object payload, String bizKey, String traceId, Throwable cause) {
            log.error("[SAMPLE-TX-FAILURE] 事务提交后发信失败补偿: topic={}, partition={}, key={}, bizKey={}, traceId={}, payload={}, cause={}",
                    topic, partition, key, bizKey, traceId, payload, cause != null ? cause.getMessage() : "unknown");
            failedRecords.add(topic + ":" + key + ":" + bizKey);
        }

        public void clear() {
            failedRecords.clear();
        }
    }
}
