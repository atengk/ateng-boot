package io.github.atengk.boot.kafka.autoconfigure;

import io.github.atengk.boot.kafka.properties.AtengKafkaProperties;
import io.github.atengk.boot.kafka.spi.KafkaTransactionFailureCallback;
import io.github.atengk.boot.kafka.template.KafkaReliableTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka 生产级增强基础设施自动装配
 *
 * <p>统一遵循防御性自动装配铁律，在 Spring 官方 {@link KafkaAutoConfiguration} 之后装配，
 * 并受控于 {@code ateng.boot.kafka.enabled} 顶层配置开关。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnProperty(prefix = "ateng.boot.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AtengKafkaProperties.class)
public class AtengKafkaAutoConfiguration {

    public AtengKafkaAutoConfiguration(AtengKafkaProperties properties) {
        log.info("[ateng-boot-kafka] Kafka 增强基础设施自动装配已激活 (sendTimeout={}, maxPayload={}, autoCreateTopics={})",
                properties.getProducer().getSendTimeout(),
                properties.getProducer().getMaxPayloadSize(),
                properties.isAutoCreateTopics());
    }

    /**
     * 生产端微批聚合与幂等参数定制器
     */
    @Bean
    @ConditionalOnMissingBean
    public DefaultKafkaProducerFactoryCustomizer atengKafkaProducerFactoryCustomizer(AtengKafkaProperties properties) {
        return new AtengKafkaProducerFactoryCustomizer(properties);
    }

    /**
     * Kafka 高可靠与事务感知发信门面
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(KafkaTemplate.class)
    public KafkaReliableTemplate kafkaReliableTemplate(
            KafkaTemplate<?, ?> kafkaTemplate,
            AtengKafkaProperties properties,
            ObjectProvider<KafkaTransactionFailureCallback> failureCallbackProvider) {
        return new KafkaReliableTemplate(kafkaTemplate, properties, failureCallbackProvider);
    }
}
