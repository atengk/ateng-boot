package io.github.atengk.boot.kafka.autoconfigure;

import io.github.atengk.boot.kafka.properties.AtengKafkaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * 消费端高可靠与反序列化防崩溃默认参数定制器
 *
 * <p>为 Kafka 消费端默认挂载 {@link ErrorHandlingDeserializer} 包裹底层的反序列化器，
 * 防止格式错误或恶意毒丸报文导致消费容器反复崩溃重启。
 * 用户显式声明的 {@code spring.kafka.consumer.*} 配置具有最高优先级，保持绝对优先。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@RequiredArgsConstructor
public class AtengKafkaConsumerFactoryCustomizer implements DefaultKafkaConsumerFactoryCustomizer {

    private final AtengKafkaProperties properties;

    @Override
    public void customize(DefaultKafkaConsumerFactory<?, ?> consumerFactory) {
        Map<String, Object> existingConfigs = consumerFactory.getConfigurationProperties();
        Map<String, Object> updates = new HashMap<>();

        // 1. 默认 Key 反序列化器：ErrorHandlingDeserializer 包裹 StringDeserializer
        if (!existingConfigs.containsKey(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG)) {
            updates.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
            updates.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class.getName());
        }

        // 2. 默认 Value 反序列化器：ErrorHandlingDeserializer 包裹 ByteArrayDeserializer (配合 JsonMessageConverter 解耦微服务与泛型)
        if (!existingConfigs.containsKey(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG)) {
            updates.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
            updates.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, ByteArrayDeserializer.class.getName());
        }

        if (!updates.isEmpty()) {
            consumerFactory.updateConfigs(updates);
            log.info("[ateng-boot-kafka] 消费端已应用 ErrorHandlingDeserializer 反序列化防崩溃加固 (key={}, value={})",
                    StringDeserializer.class.getSimpleName(),
                    ByteArrayDeserializer.class.getSimpleName());
        }
    }
}
