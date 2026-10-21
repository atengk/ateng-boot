package io.github.atengk.boot.kafka.autoconfigure;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.kafka.constant.KafkaConstants;
import io.github.atengk.boot.kafka.executor.KafkaRecordExecutor;
import io.github.atengk.boot.kafka.interceptor.KafkaBatchInterceptor;
import io.github.atengk.boot.kafka.interceptor.KafkaRecordInterceptor;
import io.github.atengk.boot.kafka.properties.AtengKafkaProperties;
import io.github.atengk.boot.kafka.spi.KafkaTransactionFailureCallback;
import io.github.atengk.boot.kafka.template.KafkaReliableTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.converter.BatchMessageConverter;
import org.springframework.kafka.support.converter.BatchMessagingMessageConverter;
import org.springframework.kafka.support.converter.ByteArrayJsonMessageConverter;
import org.springframework.kafka.support.converter.RecordMessageConverter;

/**
 * Kafka 生产级增强基础设施自动装配
 *
 * <p>统一遵循防御性自动装配铁律，在 Spring 官方 {@link KafkaAutoConfiguration} 之后装配，
 * 并受控于 {@code ateng.boot.kafka.enabled} 顶层配置开关。
 * 提供生产端调优、消费端双容器工厂、反序列化防崩溃加固、拦截体系与批量安全执行器。</p>
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
     * 消费端反序列化防崩溃参数定制器
     */
    @Bean
    @ConditionalOnMissingBean
    public DefaultKafkaConsumerFactoryCustomizer atengKafkaConsumerFactoryCustomizer(AtengKafkaProperties properties) {
        return new AtengKafkaConsumerFactoryCustomizer(properties);
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

    /**
     * 单条消费入站上下文拦截器
     */
    @Bean
    @ConditionalOnMissingBean
    public KafkaRecordInterceptor kafkaRecordInterceptor() {
        return new KafkaRecordInterceptor();
    }

    /**
     * 批量消费入站拦截器
     */
    @Bean
    @ConditionalOnMissingBean
    public KafkaBatchInterceptor kafkaBatchInterceptor() {
        return new KafkaBatchInterceptor();
    }

    /**
     * 批量记录多租户隔离与容错安全执行器
     */
    @Bean
    @ConditionalOnMissingBean
    public KafkaRecordExecutor kafkaRecordExecutor() {
        return new KafkaRecordExecutor();
    }

    /**
     * 基于 JsonUtil 的单条消息转换器（推断类型优先，支持复杂泛型）
     */
    @Bean
    @ConditionalOnMissingBean(RecordMessageConverter.class)
    public RecordMessageConverter kafkaMessageConverter() {
        return new ByteArrayJsonMessageConverter(JsonUtil.getObjectMapper());
    }

    /**
     * 批量消息转换器
     */
    @Bean
    @ConditionalOnMissingBean(BatchMessageConverter.class)
    public BatchMessageConverter kafkaBatchMessageConverter(RecordMessageConverter kafkaMessageConverter) {
        return new BatchMessagingMessageConverter(kafkaMessageConverter);
    }

    /**
     * 默认单条消费监听器容器工厂（AckMode.RECORD，挂载单条拦截器）
     */
    @Bean(name = KafkaConstants.DEFAULT_CONTAINER_FACTORY)
    @ConditionalOnMissingBean(name = KafkaConstants.DEFAULT_CONTAINER_FACTORY)
    @ConditionalOnBean(ConsumerFactory.class)
    public ConcurrentKafkaListenerContainerFactory<?, ?> kafkaListenerContainerFactory(
            ObjectProvider<ConcurrentKafkaListenerContainerFactoryConfigurer> configurerProvider,
            ConsumerFactory<Object, Object> kafkaConsumerFactory,
            KafkaRecordInterceptor kafkaRecordInterceptor,
            RecordMessageConverter kafkaMessageConverter,
            AtengKafkaProperties properties) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        ConcurrentKafkaListenerContainerFactoryConfigurer configurer = configurerProvider.getIfAvailable();
        if (configurer != null) {
            configurer.configure(factory, kafkaConsumerFactory);
        } else {
            factory.setConsumerFactory(kafkaConsumerFactory);
        }
        factory.setBatchListener(false);
        factory.setConcurrency(properties.getConsumer().getConcurrency());
        factory.setRecordInterceptor(kafkaRecordInterceptor);
        factory.setBatchInterceptor(null);
        factory.setRecordMessageConverter(kafkaMessageConverter);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }

    /**
     * 专用批量消费监听器容器工厂（batchListener=true，挂载批量拦截器）
     */
    @Bean(name = KafkaConstants.BATCH_CONTAINER_FACTORY)
    @ConditionalOnMissingBean(name = KafkaConstants.BATCH_CONTAINER_FACTORY)
    @ConditionalOnBean(ConsumerFactory.class)
    public ConcurrentKafkaListenerContainerFactory<?, ?> batchKafkaListenerContainerFactory(
            ObjectProvider<ConcurrentKafkaListenerContainerFactoryConfigurer> configurerProvider,
            ConsumerFactory<Object, Object> kafkaConsumerFactory,
            KafkaBatchInterceptor kafkaBatchInterceptor,
            BatchMessageConverter kafkaBatchMessageConverter,
            AtengKafkaProperties properties) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        ConcurrentKafkaListenerContainerFactoryConfigurer configurer = configurerProvider.getIfAvailable();
        if (configurer != null) {
            configurer.configure(factory, kafkaConsumerFactory);
        } else {
            factory.setConsumerFactory(kafkaConsumerFactory);
        }
        factory.setBatchListener(true);
        factory.setConcurrency(properties.getConsumer().getBatchConcurrency());
        factory.setBatchInterceptor(kafkaBatchInterceptor);
        factory.setRecordInterceptor(null);
        factory.setBatchMessageConverter(kafkaBatchMessageConverter);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        return factory;
    }

    /**
     * 单条监听器容器工厂后置处理器（防御 Spring Boot 官方自动装配覆盖 AckMode 与拦截器）
     */
    @Bean
    public static BeanPostProcessor kafkaListenerContainerFactoryPostProcessor(
            ObjectProvider<AtengKafkaProperties> propertiesProvider,
            ObjectProvider<KafkaRecordInterceptor> interceptorProvider,
            ObjectProvider<RecordMessageConverter> converterProvider) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (KafkaConstants.DEFAULT_CONTAINER_FACTORY.equals(beanName)
                        && bean instanceof ConcurrentKafkaListenerContainerFactory<?, ?> factory) {
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    ConcurrentKafkaListenerContainerFactory rawFactory = factory;
                    AtengKafkaProperties props = propertiesProvider.getIfAvailable();
                    rawFactory.setBatchListener(false);
                    if (props != null) {
                        rawFactory.setConcurrency(props.getConsumer().getConcurrency());
                    }
                    KafkaRecordInterceptor interceptor = interceptorProvider.getIfAvailable();
                    if (interceptor != null) {
                        rawFactory.setRecordInterceptor(interceptor);
                    }
                    rawFactory.setBatchInterceptor(null);
                    RecordMessageConverter converter = converterProvider.getIfAvailable();
                    if (converter != null) {
                        rawFactory.setRecordMessageConverter(converter);
                    }
                    rawFactory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
                }
                return bean;
            }
        };
    }
}
