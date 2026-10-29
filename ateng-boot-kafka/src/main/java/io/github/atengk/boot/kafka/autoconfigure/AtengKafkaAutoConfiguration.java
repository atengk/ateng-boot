package io.github.atengk.boot.kafka.autoconfigure;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.kafka.constant.KafkaConstants;
import io.micrometer.observation.ObservationRegistry;
import io.github.atengk.boot.kafka.executor.KafkaRecordExecutor;
import io.github.atengk.boot.kafka.interceptor.KafkaBatchInterceptor;
import io.github.atengk.boot.kafka.interceptor.KafkaRecordInterceptor;
import io.github.atengk.boot.kafka.listener.AtengDeadLetterPublishingRecoverer;
import io.github.atengk.boot.kafka.listener.AtengKafkaErrorHandler;
import io.github.atengk.boot.kafka.properties.AtengKafkaProperties;
import io.github.atengk.boot.kafka.spi.KafkaTransactionFailureCallback;
import io.github.atengk.boot.kafka.template.KafkaDeadLetterReplayTemplate;
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
import org.springframework.core.env.Environment;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.converter.BatchMessageConverter;
import org.springframework.kafka.support.converter.BatchMessagingMessageConverter;
import org.springframework.kafka.support.converter.ByteArrayJsonMessageConverter;
import org.springframework.kafka.support.converter.RecordMessageConverter;

import java.lang.reflect.Method;

/**
 * Kafka 生产级增强基础设施自动装配
 *
 * <p>统一遵循防御性自动装配铁律，在 Spring 官方 {@link KafkaAutoConfiguration} 之后装配，
 * 并受控于 {@code ateng.boot.kafka.enabled} 顶层配置开关。
 * 提供生产端调优、消费端双容器工厂、反序列化防崩溃加固、拦截体系、死信治理与批量安全执行器。</p>
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

    private final Environment environment;

    public AtengKafkaAutoConfiguration(AtengKafkaProperties properties, Environment environment) {
        this.environment = environment;
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
     * 消费端反序列化防崩溃与协作重平衡参数定制器
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
     * Kafka 死信受控治理与安全重放门面
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ConsumerFactory.class, KafkaTemplate.class})
    public KafkaDeadLetterReplayTemplate kafkaDeadLetterReplayTemplate(
            ConsumerFactory<?, ?> kafkaConsumerFactory,
            KafkaTemplate<?, ?> kafkaTemplate,
            ObjectProvider<KafkaAdmin> kafkaAdminProvider,
            AtengKafkaProperties properties) {
        return new KafkaDeadLetterReplayTemplate(kafkaConsumerFactory, kafkaTemplate, kafkaAdminProvider.getIfAvailable(), properties);
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
     * 生产级死信发布恢复器（守卫 DLT 终点不变量，幂等异常静默提交）
     */
    @Bean
    @ConditionalOnMissingBean(ConsumerRecordRecoverer.class)
    @ConditionalOnBean(KafkaOperations.class)
    public ConsumerRecordRecoverer kafkaDeadLetterPublishingRecoverer(
            KafkaOperations<?, ?> kafkaOperations,
            AtengKafkaProperties properties) {
        return new AtengDeadLetterPublishingRecoverer(kafkaOperations, properties);
    }

    /**
     * 生产级通用错误处理器（毒丸快速 0 次重试进 DLT，瞬态故障指数退避重试）
     */
    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    public CommonErrorHandler kafkaErrorHandler(
            ObjectProvider<ConsumerRecordRecoverer> recovererProvider,
            AtengKafkaProperties properties) {
        ConsumerRecordRecoverer recoverer = recovererProvider.getIfAvailable();
        if (recoverer == null) {
            recoverer = (record, exception) -> log.warn("[ateng-boot-kafka] 未提供死信恢复器，记录消费异常: topic={}, offset={}",
                    record != null ? record.topic() : "UNKNOWN",
                    record != null ? record.offset() : -1,
                    exception);
        }
        return new AtengKafkaErrorHandler(recoverer, properties);
    }

    /**
     * 默认单条消费监听器容器工厂（AckMode.RECORD，挂载单条拦截器，10s优雅停机，虚拟线程与 Observation 自适应）
     */
    @Bean(name = KafkaConstants.DEFAULT_CONTAINER_FACTORY)
    @ConditionalOnMissingBean(name = KafkaConstants.DEFAULT_CONTAINER_FACTORY)
    @ConditionalOnBean(ConsumerFactory.class)
    public ConcurrentKafkaListenerContainerFactory<?, ?> kafkaListenerContainerFactory(
            ObjectProvider<ConcurrentKafkaListenerContainerFactoryConfigurer> configurerProvider,
            ConsumerFactory<Object, Object> kafkaConsumerFactory,
            KafkaRecordInterceptor kafkaRecordInterceptor,
            RecordMessageConverter kafkaMessageConverter,
            ObjectProvider<CommonErrorHandler> errorHandlerProvider,
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
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
        CommonErrorHandler errorHandler = errorHandlerProvider.getIfAvailable();
        if (errorHandler != null) {
            factory.setCommonErrorHandler(errorHandler);
        }
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setShutdownTimeout(properties.getConsumer().getShutdownTimeout().toMillis());
        applyVirtualThreadIfEnabled(factory, environment);
        applyObservationIfPresent(factory, observationRegistryProvider);
        return factory;
    }

    /**
     * 专用批量消费监听器容器工厂（batchListener=true，挂载批量拦截器，10s优雅停机，虚拟线程与 Observation 自适应）
     */
    @Bean(name = KafkaConstants.BATCH_CONTAINER_FACTORY)
    @ConditionalOnMissingBean(name = KafkaConstants.BATCH_CONTAINER_FACTORY)
    @ConditionalOnBean(ConsumerFactory.class)
    public ConcurrentKafkaListenerContainerFactory<?, ?> batchKafkaListenerContainerFactory(
            ObjectProvider<ConcurrentKafkaListenerContainerFactoryConfigurer> configurerProvider,
            ConsumerFactory<Object, Object> kafkaConsumerFactory,
            KafkaBatchInterceptor kafkaBatchInterceptor,
            BatchMessageConverter kafkaBatchMessageConverter,
            ObjectProvider<CommonErrorHandler> errorHandlerProvider,
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
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
        CommonErrorHandler errorHandler = errorHandlerProvider.getIfAvailable();
        if (errorHandler != null) {
            factory.setCommonErrorHandler(errorHandler);
        }
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        factory.getContainerProperties().setShutdownTimeout(properties.getConsumer().getShutdownTimeout().toMillis());
        applyVirtualThreadIfEnabled(factory, environment);
        applyObservationIfPresent(factory, observationRegistryProvider);
        return factory;
    }

    /**
     * 单条监听器容器工厂后置处理器（防御 Spring Boot 官方自动装配覆盖 AckMode 与拦截器）
     */
    @Bean
    @ConditionalOnMissingBean(name = "kafkaListenerContainerFactoryPostProcessor")
    public static BeanPostProcessor kafkaListenerContainerFactoryPostProcessor(
            ObjectProvider<AtengKafkaProperties> propertiesProvider,
            ObjectProvider<KafkaRecordInterceptor> interceptorProvider,
            ObjectProvider<RecordMessageConverter> converterProvider,
            ObjectProvider<CommonErrorHandler> errorHandlerProvider,
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            Environment environment) {
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
                        rawFactory.getContainerProperties().setShutdownTimeout(props.getConsumer().getShutdownTimeout().toMillis());
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
                    CommonErrorHandler errorHandler = errorHandlerProvider.getIfAvailable();
                    if (errorHandler != null) {
                        rawFactory.setCommonErrorHandler(errorHandler);
                    }
                    rawFactory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
                    applyVirtualThreadIfEnabled(rawFactory, environment);
                    applyObservationIfPresent(rawFactory, observationRegistryProvider);
                }
                return bean;
            }
        };
    }

    /**
     * KafkaAdmin 后置处理器：管控 autoCreateTopics 开关（只读账号安全防卫）
     */
    @Bean
    @ConditionalOnMissingBean(name = "kafkaAdminPostProcessor")
    public static BeanPostProcessor kafkaAdminPostProcessor(ObjectProvider<AtengKafkaProperties> propertiesProvider) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof KafkaAdmin kafkaAdmin) {
                    AtengKafkaProperties props = propertiesProvider.getIfAvailable();
                    if (props != null) {
                        kafkaAdmin.setAutoCreate(props.isAutoCreateTopics());
                    }
                }
                return bean;
            }
        };
    }

    private static void applyObservationIfPresent(
            ConcurrentKafkaListenerContainerFactory<?, ?> factory,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        if (factory != null && observationRegistryProvider != null) {
            observationRegistryProvider.ifAvailable(registry -> factory.getContainerProperties().setObservationEnabled(true));
        }
    }

    private static void applyVirtualThreadIfEnabled(ConcurrentKafkaListenerContainerFactory<?, ?> factory, Environment environment) {
        if (environment == null || factory == null) {
            return;
        }
        boolean virtualEnabled = environment.getProperty("spring.threads.virtual.enabled", Boolean.class, false);
        if (virtualEnabled && isVirtualThreadsAvailable()) {
            try {
                VirtualThreadTaskExecutor vtExecutor = new VirtualThreadTaskExecutor("kafka-vt-");
                factory.getContainerProperties().setListenerTaskExecutor(vtExecutor);
                log.info("[ateng-boot-kafka] Kafka 监听容器已接入 JDK 虚拟线程执行器 (VirtualThreadTaskExecutor)");
            } catch (Throwable ex) {
                log.warn("[ateng-boot-kafka] 尝试激活虚拟线程执行器失败，回退至平台线程: {}", ex.getMessage());
            }
        }
    }

    private static boolean isVirtualThreadsAvailable() {
        try {
            Method method = Thread.class.getMethod("ofVirtual");
            return method != null;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
