package io.github.atengk.boot.kafka.autoconfigure;

import io.github.atengk.boot.kafka.properties.AtengKafkaProperties;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 生产端微批与幂等参数定制器单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("AtengKafkaProducerFactoryCustomizer 单元测试")
class AtengKafkaProducerFactoryCustomizerTest {

    @Test
    @DisplayName("默认启用调优时应用生产级微批与幂等参数")
    void testDefaultCustomization() {
        AtengKafkaProperties properties = new AtengKafkaProperties();
        AtengKafkaProducerFactoryCustomizer customizer = new AtengKafkaProducerFactoryCustomizer(properties);

        Map<String, Object> baseConfigs = new HashMap<>();
        baseConfigs.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        DefaultKafkaProducerFactory<Object, Object> factory = new DefaultKafkaProducerFactory<>(baseConfigs);

        customizer.customize(factory);

        Map<String, Object> configs = factory.getConfigurationProperties();
        assertEquals("all", configs.get(ProducerConfig.ACKS_CONFIG));
        assertEquals(true, configs.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG));
        assertEquals(Integer.MAX_VALUE, configs.get(ProducerConfig.RETRIES_CONFIG));
        assertEquals(5, configs.get(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION));
        assertEquals(32768, configs.get(ProducerConfig.BATCH_SIZE_CONFIG));
        assertEquals(10, configs.get(ProducerConfig.LINGER_MS_CONFIG));
        assertEquals("snappy", configs.get(ProducerConfig.COMPRESSION_TYPE_CONFIG));
        assertEquals(33554432L, configs.get(ProducerConfig.BUFFER_MEMORY_CONFIG));
        assertEquals(60000L, configs.get(ProducerConfig.MAX_BLOCK_MS_CONFIG));
    }

    @Test
    @DisplayName("用户显式配置参数具有最高优先级，不被默认值覆盖")
    void testUserConfigPrecedence() {
        AtengKafkaProperties properties = new AtengKafkaProperties();
        AtengKafkaProducerFactoryCustomizer customizer = new AtengKafkaProducerFactoryCustomizer(properties);

        Map<String, Object> baseConfigs = new HashMap<>();
        baseConfigs.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        baseConfigs.put(ProducerConfig.ACKS_CONFIG, "1");
        baseConfigs.put(ProducerConfig.BATCH_SIZE_CONFIG, 65536);
        baseConfigs.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");

        DefaultKafkaProducerFactory<Object, Object> factory = new DefaultKafkaProducerFactory<>(baseConfigs);

        customizer.customize(factory);

        Map<String, Object> configs = factory.getConfigurationProperties();
        assertEquals("1", configs.get(ProducerConfig.ACKS_CONFIG)); // 保持用户配置
        assertEquals(65536, configs.get(ProducerConfig.BATCH_SIZE_CONFIG)); // 保持用户配置
        assertEquals("zstd", configs.get(ProducerConfig.COMPRESSION_TYPE_CONFIG)); // 保持用户配置

        // 其余未显式配置项仍然应用默认调优
        assertEquals(10, configs.get(ProducerConfig.LINGER_MS_CONFIG));
        assertEquals(true, configs.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG));
    }

    @Test
    @DisplayName("禁用调优开关 (defaultTuningEnabled=false) 时不添加任何参数")
    void testDisabledTuning() {
        AtengKafkaProperties properties = new AtengKafkaProperties();
        properties.getProducer().setDefaultTuningEnabled(false);
        AtengKafkaProducerFactoryCustomizer customizer = new AtengKafkaProducerFactoryCustomizer(properties);

        Map<String, Object> baseConfigs = new HashMap<>();
        baseConfigs.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        DefaultKafkaProducerFactory<Object, Object> factory = new DefaultKafkaProducerFactory<>(baseConfigs);

        customizer.customize(factory);

        Map<String, Object> configs = factory.getConfigurationProperties();
        assertNull(configs.get(ProducerConfig.ACKS_CONFIG));
        assertNull(configs.get(ProducerConfig.BATCH_SIZE_CONFIG));
    }
}
