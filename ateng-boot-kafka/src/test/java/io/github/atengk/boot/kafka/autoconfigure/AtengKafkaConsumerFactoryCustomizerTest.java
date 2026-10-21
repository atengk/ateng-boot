package io.github.atengk.boot.kafka.autoconfigure;

import io.github.atengk.boot.kafka.properties.AtengKafkaProperties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.IntegerDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消费端定制器单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("AtengKafkaConsumerFactoryCustomizer 消费端定制器单元测试")
class AtengKafkaConsumerFactoryCustomizerTest {

    private final AtengKafkaProperties properties = new AtengKafkaProperties();
    private final AtengKafkaConsumerFactoryCustomizer customizer = new AtengKafkaConsumerFactoryCustomizer(properties);

    @Test
    @DisplayName("默认情况下应用 ErrorHandlingDeserializer 包裹 String 与 ByteArray 反序列化器")
    void testDefaultCustomization() {
        DefaultKafkaConsumerFactory<Object, Object> factory = new DefaultKafkaConsumerFactory<>(new HashMap<>());
        customizer.customize(factory);

        Map<String, Object> configs = factory.getConfigurationProperties();

        assertThat(configs.get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG)).isEqualTo(ErrorHandlingDeserializer.class);
        assertThat(configs.get(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS)).isEqualTo(StringDeserializer.class.getName());

        assertThat(configs.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG)).isEqualTo(ErrorHandlingDeserializer.class);
        assertThat(configs.get(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS)).isEqualTo(ByteArrayDeserializer.class.getName());
    }

    @Test
    @DisplayName("用户显式配置反序列化器时保持最高优先级，不被框架覆盖")
    void testUserConfigPrecedence() {
        Map<String, Object> initialConfigs = new HashMap<>();
        initialConfigs.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, IntegerDeserializer.class);
        initialConfigs.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        DefaultKafkaConsumerFactory<Object, Object> factory = new DefaultKafkaConsumerFactory<>(initialConfigs);
        customizer.customize(factory);

        Map<String, Object> configs = factory.getConfigurationProperties();

        // 保持用户原有配置
        assertThat(configs.get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG)).isEqualTo(IntegerDeserializer.class);
        assertThat(configs.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG)).isEqualTo(StringDeserializer.class);
    }
}
