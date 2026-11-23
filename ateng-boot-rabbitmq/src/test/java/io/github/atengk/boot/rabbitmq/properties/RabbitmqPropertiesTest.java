package io.github.atengk.boot.rabbitmq.properties;

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
 * RabbitMQ 配置属性单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("RabbitmqProperties 配置属性单元测试")
class RabbitmqPropertiesTest {

    @Test
    @DisplayName("验证默认生产级合理配置项")
    void testDefaultValues() {
        RabbitmqProperties properties = new RabbitmqProperties();

        assertTrue(properties.isEnabled());

        // Producer
        assertEquals(DataSize.ofMegabytes(2), properties.getProducer().getMaxPayloadSize());
        assertEquals(DataSize.ofKilobytes(2), properties.getProducer().getCompressionThreshold());
        assertTrue(properties.getProducer().isCompressionEnabled());

        // Reliable
        assertEquals(Duration.ofSeconds(3), properties.getReliable().getDefaultTimeout());
        assertTrue(properties.getReliable().isReturnsCallbackEnabled());

        // Consumer
        assertEquals(20, properties.getConsumer().getPrefetch());
        assertEquals(1, properties.getConsumer().getConcurrency());
        assertEquals(8, properties.getConsumer().getMaxConcurrency());
        assertEquals(Duration.ofSeconds(30), properties.getConsumer().getShutdownTimeout());

        // Consumer Retry
        assertTrue(properties.getConsumer().getRetry().isEnabled());
        assertEquals(3, properties.getConsumer().getRetry().getMaxAttempts());
        assertEquals(Duration.ofSeconds(1), properties.getConsumer().getRetry().getInitialInterval());
        assertEquals(2.0, properties.getConsumer().getRetry().getMultiplier());
        assertEquals(Duration.ofSeconds(10), properties.getConsumer().getRetry().getMaxInterval());

        // Consumer Batch
        assertFalse(properties.getConsumer().getBatch().isEnabled());
        assertEquals(50, properties.getConsumer().getBatch().getSize());
        assertEquals(Duration.ofMillis(500), properties.getConsumer().getBatch().getMaxWaitTime());

        // Topology
        assertTrue(properties.getTopology().isAutoDeclare());

        // Logging
        assertTrue(properties.getLogging().isEnabled());
        assertEquals(1024, properties.getLogging().getLogPayloadMaxLength());
    }

    @Test
    @DisplayName("验证 Spring Binder 属性绑定与覆盖")
    void testPropertyBinding() {
        Map<String, Object> map = new HashMap<>();
        map.put("ateng.boot.rabbitmq.enabled", "false");
        map.put("ateng.boot.rabbitmq.producer.max-payload-size", "5MB");
        map.put("ateng.boot.rabbitmq.producer.compression-threshold", "4KB");
        map.put("ateng.boot.rabbitmq.reliable.default-timeout", "5s");
        map.put("ateng.boot.rabbitmq.consumer.prefetch", "50");
        map.put("ateng.boot.rabbitmq.consumer.concurrency", "2");
        map.put("ateng.boot.rabbitmq.consumer.max-concurrency", "16");
        map.put("ateng.boot.rabbitmq.consumer.shutdown-timeout", "45s");
        map.put("ateng.boot.rabbitmq.consumer.retry.max-attempts", "5");
        map.put("ateng.boot.rabbitmq.consumer.batch.enabled", "true");
        map.put("ateng.boot.rabbitmq.consumer.batch.size", "100");
        map.put("ateng.boot.rabbitmq.consumer.batch.max-wait-time", "1s");
        map.put("ateng.boot.rabbitmq.topology.auto-declare", "false");

        ConfigurationPropertySource source = new MapConfigurationPropertySource(map);
        Binder binder = new Binder(source);

        RabbitmqProperties properties = binder.bind("ateng.boot.rabbitmq", RabbitmqProperties.class).get();

        assertFalse(properties.isEnabled());
        assertEquals(DataSize.ofMegabytes(5), properties.getProducer().getMaxPayloadSize());
        assertEquals(DataSize.ofKilobytes(4), properties.getProducer().getCompressionThreshold());
        assertEquals(Duration.ofSeconds(5), properties.getReliable().getDefaultTimeout());
        assertEquals(50, properties.getConsumer().getPrefetch());
        assertEquals(2, properties.getConsumer().getConcurrency());
        assertEquals(16, properties.getConsumer().getMaxConcurrency());
        assertEquals(Duration.ofSeconds(45), properties.getConsumer().getShutdownTimeout());
        assertEquals(5, properties.getConsumer().getRetry().getMaxAttempts());
        assertTrue(properties.getConsumer().getBatch().isEnabled());
        assertEquals(100, properties.getConsumer().getBatch().getSize());
        assertEquals(Duration.ofSeconds(1), properties.getConsumer().getBatch().getMaxWaitTime());
        assertFalse(properties.getTopology().isAutoDeclare());
    }
}
