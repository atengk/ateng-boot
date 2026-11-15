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
        assertEquals(DataSize.ofKilobytes(32), properties.getProducer().getBatchSize());
        assertEquals(Duration.ofMillis(10), properties.getProducer().getLinger());
        assertEquals("snappy", properties.getProducer().getCompressionType());
        assertEquals(DataSize.ofMegabytes(32), properties.getProducer().getBufferMemory());
        assertEquals(Duration.ofSeconds(60), properties.getProducer().getMaxBlock());

        // Consumer
        assertEquals(3, properties.getConsumer().getConcurrency());
        assertEquals(3, properties.getConsumer().getBatchConcurrency());
        assertEquals(3, properties.getConsumer().getMaxRetries());
        assertEquals(Duration.ofSeconds(1), properties.getConsumer().getRetryInterval());
        assertEquals(2.0, properties.getConsumer().getRetryMultiplier());
        assertEquals(Duration.ofSeconds(30), properties.getConsumer().getMaxRetryInterval());
        assertEquals(Duration.ofSeconds(10), properties.getConsumer().getShutdownTimeout());
        assertEquals(".DLT", properties.getConsumer().getDltSuffix());

        // DeadLetter
        assertEquals(100, properties.getDeadLetter().getDefaultReplayLimit());
        assertEquals(1, properties.getDeadLetter().getMaxReplayCount());
        assertEquals("ateng-kafka-dlt-replay-group", properties.getDeadLetter().getReplayGroupId());
        assertEquals(Duration.ofSeconds(5), properties.getDeadLetter().getPeekTimeout());
        assertEquals(Duration.ofSeconds(10), properties.getDeadLetter().getOperationTimeout());

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
        map.put("ateng.boot.kafka.producer.batch-size", "64KB");
        map.put("ateng.boot.kafka.producer.linger", "25ms");
        map.put("ateng.boot.kafka.producer.compression-type", "zstd");
        map.put("ateng.boot.kafka.producer.buffer-memory", "64MB");
        map.put("ateng.boot.kafka.producer.max-block", "45s");
        map.put("ateng.boot.kafka.consumer.concurrency", "6");
        map.put("ateng.boot.kafka.consumer.batch-concurrency", "8");
        map.put("ateng.boot.kafka.consumer.max-retries", "5");
        map.put("ateng.boot.kafka.consumer.retry-interval", "2s");
        map.put("ateng.boot.kafka.consumer.retry-multiplier", "3.0");
        map.put("ateng.boot.kafka.consumer.max-retry-interval", "45s");
        map.put("ateng.boot.kafka.consumer.shutdown-timeout", "20s");
        map.put("ateng.boot.kafka.consumer.dlt-suffix", ".DEAD_LETTER");
        map.put("ateng.boot.kafka.dead-letter.default-replay-limit", "200");
        map.put("ateng.boot.kafka.dead-letter.max-replay-count", "3");
        map.put("ateng.boot.kafka.dead-letter.replay-group-id", "custom-replay-group");
        map.put("ateng.boot.kafka.dead-letter.peek-timeout", "8s");
        map.put("ateng.boot.kafka.dead-letter.operation-timeout", "25s");
        map.put("ateng.boot.kafka.offset.timeout", "15s");

        ConfigurationPropertySource source = new MapConfigurationPropertySource(map);
        Binder binder = new Binder(source);

        AtengKafkaProperties properties = binder.bind("ateng.boot.kafka", AtengKafkaProperties.class).get();

        assertTrue(properties.isEnabled());
        assertFalse(properties.isAutoCreateTopics());

        assertEquals(Duration.ofSeconds(10), properties.getProducer().getSendTimeout());
        assertEquals(DataSize.ofMegabytes(2), properties.getProducer().getMaxPayloadSize());
        assertFalse(properties.getProducer().isDefaultTuningEnabled());
        assertEquals(DataSize.ofKilobytes(64), properties.getProducer().getBatchSize());
        assertEquals(Duration.ofMillis(25), properties.getProducer().getLinger());
        assertEquals("zstd", properties.getProducer().getCompressionType());
        assertEquals(DataSize.ofMegabytes(64), properties.getProducer().getBufferMemory());
        assertEquals(Duration.ofSeconds(45), properties.getProducer().getMaxBlock());

        assertEquals(6, properties.getConsumer().getConcurrency());
        assertEquals(8, properties.getConsumer().getBatchConcurrency());
        assertEquals(5, properties.getConsumer().getMaxRetries());
        assertEquals(Duration.ofSeconds(2), properties.getConsumer().getRetryInterval());
        assertEquals(3.0, properties.getConsumer().getRetryMultiplier());
        assertEquals(Duration.ofSeconds(45), properties.getConsumer().getMaxRetryInterval());
        assertEquals(Duration.ofSeconds(20), properties.getConsumer().getShutdownTimeout());
        assertEquals(".DEAD_LETTER", properties.getConsumer().getDltSuffix());

        assertEquals(200, properties.getDeadLetter().getDefaultReplayLimit());
        assertEquals(3, properties.getDeadLetter().getMaxReplayCount());
        assertEquals("custom-replay-group", properties.getDeadLetter().getReplayGroupId());
        assertEquals(Duration.ofSeconds(8), properties.getDeadLetter().getPeekTimeout());
        assertEquals(Duration.ofSeconds(25), properties.getDeadLetter().getOperationTimeout());

        assertEquals(Duration.ofSeconds(15), properties.getOffset().getTimeout());
    }

    @Test
    @DisplayName("死信超时配置正数防线：拒绝 null、零与负时长")
    void testDeadLetterTimeoutPositiveGuard() {
        AtengKafkaProperties.DeadLetter deadLetter = new AtengKafkaProperties.DeadLetter();
        assertThrows(IllegalArgumentException.class, () -> deadLetter.setPeekTimeout(null));
        assertThrows(IllegalArgumentException.class, () -> deadLetter.setPeekTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> deadLetter.setPeekTimeout(Duration.ofMillis(-10)));
        assertThrows(IllegalArgumentException.class, () -> deadLetter.setPeekTimeout(Duration.ofNanos(1)));
        assertThrows(IllegalArgumentException.class, () -> deadLetter.setOperationTimeout(null));
        assertThrows(IllegalArgumentException.class, () -> deadLetter.setOperationTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> deadLetter.setOperationTimeout(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> deadLetter.setOperationTimeout(Duration.ofNanos(500)));

        Binder invalidPeekBinder = new Binder(new MapConfigurationPropertySource(
                Map.of("ateng.boot.kafka.dead-letter.peek-timeout", "0s")
        ));
        assertThrows(Exception.class, () -> invalidPeekBinder.bind("ateng.boot.kafka", AtengKafkaProperties.class));

        Binder invalidOpBinder = new Binder(new MapConfigurationPropertySource(
                Map.of("ateng.boot.kafka.dead-letter.operation-timeout", "-5s")
        ));
        assertThrows(Exception.class, () -> invalidOpBinder.bind("ateng.boot.kafka", AtengKafkaProperties.class));
    }

    @Test
    @DisplayName("生产与消费调优配置合法范围防线：拒绝非法空值、负值与越界值")
    void testProducerAndConsumerTuningValidation() {
        AtengKafkaProperties.Producer producer = new AtengKafkaProperties.Producer();
        assertThrows(IllegalArgumentException.class, () -> producer.setBatchSize(null));
        assertThrows(IllegalArgumentException.class, () -> producer.setBatchSize(DataSize.ofBytes(-1)));
        assertThrows(IllegalArgumentException.class, () -> producer.setBatchSize(DataSize.ofBytes((long) Integer.MAX_VALUE + 1L)));

        assertThrows(IllegalArgumentException.class, () -> producer.setLinger(null));
        assertThrows(IllegalArgumentException.class, () -> producer.setLinger(Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class, () -> producer.setLinger(Duration.ofMillis((long) Integer.MAX_VALUE + 1L)));
        assertThrows(IllegalArgumentException.class, () -> producer.setLinger(Duration.ofDays(365_000_000L)));

        assertThrows(IllegalArgumentException.class, () -> producer.setCompressionType(null));
        assertThrows(IllegalArgumentException.class, () -> producer.setCompressionType(" "));
        assertThrows(IllegalArgumentException.class, () -> producer.setCompressionType("brotli"));
        for (String codec : java.util.List.of("none", "gzip", "snappy", "lz4", "zstd")) {
            producer.setCompressionType(codec);
            assertEquals(codec, producer.getCompressionType());
        }
        producer.setCompressionType(" ZSTD ");
        assertEquals("zstd", producer.getCompressionType());

        assertThrows(IllegalArgumentException.class, () -> producer.setBufferMemory(null));
        assertThrows(IllegalArgumentException.class, () -> producer.setBufferMemory(DataSize.ofBytes(0)));
        assertThrows(IllegalArgumentException.class, () -> producer.setBufferMemory(DataSize.ofBytes(-1024)));

        assertThrows(IllegalArgumentException.class, () -> producer.setMaxBlock(null));
        assertThrows(IllegalArgumentException.class, () -> producer.setMaxBlock(Duration.ofMillis(-1)));

        // 允许合法零值与 Integer.MAX_VALUE 精确上界
        producer.setBatchSize(DataSize.ofBytes(0));
        producer.setLinger(Duration.ZERO);
        producer.setMaxBlock(Duration.ZERO);
        assertEquals(0L, producer.getBatchSize().toBytes());
        assertEquals(0L, producer.getLinger().toMillis());
        assertEquals(0L, producer.getMaxBlock().toMillis());

        producer.setBatchSize(DataSize.ofBytes(Integer.MAX_VALUE));
        producer.setLinger(Duration.ofMillis(Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, producer.getBatchSize().toBytes());
        assertEquals(Integer.MAX_VALUE, producer.getLinger().toMillis());

        AtengKafkaProperties.Consumer consumer = new AtengKafkaProperties.Consumer();
        assertThrows(IllegalArgumentException.class, () -> consumer.setMaxRetryInterval(null));
        assertThrows(IllegalArgumentException.class, () -> consumer.setMaxRetryInterval(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> consumer.setMaxRetryInterval(Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class, () -> consumer.setMaxRetryInterval(Duration.ofNanos(100)));

        for (Map.Entry<String, String> invalidProp : Map.of(
                "ateng.boot.kafka.producer.compression-type", "invalid-codec",
                "ateng.boot.kafka.producer.batch-size", "-1B",
                "ateng.boot.kafka.producer.linger", "-5ms",
                "ateng.boot.kafka.producer.buffer-memory", "0B",
                "ateng.boot.kafka.producer.max-block", "-1s",
                "ateng.boot.kafka.consumer.max-retry-interval", "0s"
        ).entrySet()) {
            Binder invalidBinder = new Binder(new MapConfigurationPropertySource(
                    Map.of(invalidProp.getKey(), invalidProp.getValue())
            ));
            assertThrows(Exception.class, () -> invalidBinder.bind("ateng.boot.kafka", AtengKafkaProperties.class));
        }
    }
}
