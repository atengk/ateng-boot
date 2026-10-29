package io.github.atengk.boot.kafka.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kafka 增强底座配置属性单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("AtengKafkaProperties 配置属性单元测试")
class AtengKafkaPropertiesTest {

    @Test
    @DisplayName("验证默认生产级合理配置项")
    void testDefaultValues() {
        AtengKafkaProperties properties = new AtengKafkaProperties();

        assertTrue(properties.isEnabled());
        assertTrue(properties.isAutoCreateTopics());

        // Producer
        assertEquals(Duration.ofSeconds(5), properties.getProducer().getSendTimeout());
        assertEquals(DataSize.ofMegabytes(1), properties.getProducer().getMaxPayloadSize());
        assertTrue(properties.getProducer().isDefaultTuningEnabled());

        // Consumer
        assertEquals(3, properties.getConsumer().getConcurrency());
        assertEquals(3, properties.getConsumer().getBatchConcurrency());
        assertEquals(3, properties.getConsumer().getMaxRetries());
        assertEquals(Duration.ofSeconds(1), properties.getConsumer().getRetryInterval());
        assertEquals(2.0, properties.getConsumer().getRetryMultiplier());
        assertEquals(Duration.ofSeconds(10), properties.getConsumer().getShutdownTimeout());
        assertEquals(".DLT", properties.getConsumer().getDltSuffix());

        // DeadLetter
        assertEquals(100, properties.getDeadLetter().getDefaultReplayLimit());
        assertEquals(1, properties.getDeadLetter().getMaxReplayCount());
        assertEquals("ateng-kafka-dlt-replay-group", properties.getDeadLetter().getReplayGroupId());

        // Offset
        assertEquals(Duration.ofSeconds(10), properties.getOffset().getTimeout());
    }

    @Test
    @DisplayName("验证自定义 YAML 属性绑定与 Duration/DataSize 转换")
    void testCustomBinding() {
        Map<String, String> map = new HashMap<>();
        map.put("ateng.boot.kafka.enabled", "true");
        map.put("ateng.boot.kafka.auto-create-topics", "false");
        map.put("ateng.boot.kafka.producer.send-timeout", "10s");
        map.put("ateng.boot.kafka.producer.max-payload-size", "2MB");
        map.put("ateng.boot.kafka.producer.default-tuning-enabled", "false");
        map.put("ateng.boot.kafka.consumer.concurrency", "6");
        map.put("ateng.boot.kafka.consumer.batch-concurrency", "8");
        map.put("ateng.boot.kafka.consumer.max-retries", "5");
        map.put("ateng.boot.kafka.consumer.retry-interval", "2s");
        map.put("ateng.boot.kafka.consumer.retry-multiplier", "3.0");
        map.put("ateng.boot.kafka.consumer.shutdown-timeout", "20s");
        map.put("ateng.boot.kafka.consumer.dlt-suffix", ".DEAD_LETTER");
        map.put("ateng.boot.kafka.dead-letter.default-replay-limit", "200");
        map.put("ateng.boot.kafka.dead-letter.max-replay-count", "3");
        map.put("ateng.boot.kafka.dead-letter.replay-group-id", "custom-replay-group");
        map.put("ateng.boot.kafka.offset.timeout", "15s");

        ConfigurationPropertySource source = new MapConfigurationPropertySource(map);
        Binder binder = new Binder(source);

        AtengKafkaProperties properties = binder.bind("ateng.boot.kafka", AtengKafkaProperties.class).get();

        assertTrue(properties.isEnabled());
        assertFalse(properties.isAutoCreateTopics());

        assertEquals(Duration.ofSeconds(10), properties.getProducer().getSendTimeout());
        assertEquals(DataSize.ofMegabytes(2), properties.getProducer().getMaxPayloadSize());
        assertFalse(properties.getProducer().isDefaultTuningEnabled());

        assertEquals(6, properties.getConsumer().getConcurrency());
        assertEquals(8, properties.getConsumer().getBatchConcurrency());
        assertEquals(5, properties.getConsumer().getMaxRetries());
        assertEquals(Duration.ofSeconds(2), properties.getConsumer().getRetryInterval());
        assertEquals(3.0, properties.getConsumer().getRetryMultiplier());
        assertEquals(Duration.ofSeconds(20), properties.getConsumer().getShutdownTimeout());
        assertEquals(".DEAD_LETTER", properties.getConsumer().getDltSuffix());

        assertEquals(200, properties.getDeadLetter().getDefaultReplayLimit());
        assertEquals(3, properties.getDeadLetter().getMaxReplayCount());
        assertEquals("custom-replay-group", properties.getDeadLetter().getReplayGroupId());

        assertEquals(Duration.ofSeconds(15), properties.getOffset().getTimeout());
    }
}
