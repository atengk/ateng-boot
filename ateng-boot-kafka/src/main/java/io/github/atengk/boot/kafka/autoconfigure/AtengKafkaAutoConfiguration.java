package io.github.atengk.boot.kafka.autoconfigure;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.kafka.constant.KafkaConstants;
import io.micrometer.observation.ObservationRegistry;
import io.github.atengk.boot.kafka.executor.KafkaRecordExecutor;
import io.github.atengk.boot.kafka.interceptor.KafkaBatchInterceptor;
import io.github.atengk.boot.kafka.interceptor.KafkaRecordInterceptor;
import io.github.atengk.boot.kafka.listener.AtengDeadLetterPublishingRecoverer;
import io.github.atengk.boot.kafka.listener.AtengKafkaErrorHandler;
import io.github.atengk.boot.kafka.manager.KafkaContainerManager;
import io.github.atengk.boot.kafka.manager.KafkaOffsetManager;
import io.github.atengk.boot.kafka.properties.AtengKafkaProperties;
import io.github.atengk.boot.kafka.spi.KafkaAdminClientFactory;
import io.github.atengk.boot.kafka.spi.KafkaRebalanceListener;
import io.github.atengk.boot.kafka.spi.KafkaTransactionFailureCallback;
import io.github.atengk.boot.kafka.template.KafkaDeadLetterReplayTemplate;
import io.github.atengk.boot.kafka.template.KafkaReliableTemplate;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
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
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.converter.BatchMessageConverter;
import org.springframework.kafka.support.converter.BatchMessagingMessageConverter;
import org.springframework.kafka.support.converter.ByteArrayJsonMessageConverter;
import org.springframework.kafka.support.converter.RecordMessageConverter;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

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
            ObjectProvider<KafkaAdminClientFactory> adminClientFactoryProvider,
            AtengKafkaProperties properties) {
        KafkaAdmin kafkaAdmin = kafkaAdminProvider.getIfAvailable();
        Supplier<AdminClient> adminClientSupplier = resolveAdminClientSupplier(
                adminClientFactoryProvider.getIfAvailable(), kafkaAdmin, kafkaConsumerFactory);
        return new KafkaDeadLetterReplayTemplate(
                kafkaConsumerFactory, kafkaTemplate, kafkaAdmin, properties, adminClientSupplier);
    }

    /**
     * Kafka 位移治理与实时 Lag 积压探测门面
     */
    @Bean
    @ConditionalOnMissingBean
    public KafkaOffsetManager kafkaOffsetManager(
            ObjectProvider<KafkaAdmin> kafkaAdminProvider,
            ObjectProvider<ConsumerFactory<?, ?>> consumerFactoryProvider,
            ObjectProvider<KafkaAdminClientFactory> adminClientFactoryProvider,
            AtengKafkaProperties properties) {
        KafkaAdmin kafkaAdmin = kafkaAdminProvider.getIfAvailable();
        ConsumerFactory<?, ?> consumerFactory = consumerFactoryProvider.getIfAvailable();
        Supplier<AdminClient> adminClientSupplier = resolveAdminClientSupplier(
                adminClientFactoryProvider.getIfAvailable(), kafkaAdmin, consumerFactory);
        return new KafkaOffsetManager(kafkaAdmin, properties, consumerFactory, adminClientSupplier);
    }

    private static Supplier<AdminClient> resolveAdminClientSupplier(
            KafkaAdminClientFactory factory,
            KafkaAdmin kafkaAdmin,
            ConsumerFactory<?, ?> consumerFactory) {
        if (factory == null) {
            return null;
        }
        return () -> {
            Map<String, Object> props = Collections.emptyMap();
            if (kafkaAdmin != null && kafkaAdmin.getConfigurationProperties() != null) {
                props = kafkaAdmin.getConfigurationProperties();
            } else if (consumerFactory != null && consumerFactory.getConfigurationProperties() != null) {
                props = consumerFactory.getConfigurationProperties();
            }
            return factory.create(props);
        };
    }

    /**
     * Kafka 运行期消费监听容器动态流控与管控门面
     */
    @Bean
    @ConditionalOnMissingBean
    public KafkaContainerManager kafkaContainerManager(ObjectProvider<KafkaListenerEndpointRegistry> registryProvider) {
        return new KafkaContainerManager(registryProvider.getIfAvailable());
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
            ObjectProvider<KafkaRebalanceListener> rebalanceListenerProvider,
            AtengKafkaProperties properties) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        applyConfigurerOrConsumerFactory(factory, configurerProvider.getIfAvailable(), kafkaConsumerFactory);
        ContainerFactoryCollaborators collaborators = new ContainerFactoryCollaborators(
                properties, errorHandlerProvider, observationRegistryProvider, rebalanceListenerProvider, environment);
        configureDefaultContainerFactory(factory, kafkaRecordInterceptor, kafkaMessageConverter, collaborators);
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
            ObjectProvider<KafkaRebalanceListener> rebalanceListenerProvider,
            AtengKafkaProperties properties) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        applyConfigurerOrConsumerFactory(factory, configurerProvider.getIfAvailable(), kafkaConsumerFactory);
        ContainerFactoryCollaborators collaborators = new ContainerFactoryCollaborators(
                properties, errorHandlerProvider, observationRegistryProvider, rebalanceListenerProvider, environment);
        configureBatchContainerFactory(factory, kafkaBatchInterceptor, kafkaBatchMessageConverter, collaborators);
        return factory;
    }

    /**
     * 单条与批量监听器容器工厂后置处理器（防御 Spring Boot 官方自动装配覆盖 AckMode 与拦截器）
     */
    @Bean
    @ConditionalOnMissingBean(name = "kafkaListenerContainerFactoryPostProcessor")
    public static BeanPostProcessor kafkaListenerContainerFactoryPostProcessor(
            ObjectProvider<AtengKafkaProperties> propertiesProvider,
            ObjectProvider<KafkaRecordInterceptor> interceptorProvider,
            ObjectProvider<RecordMessageConverter> converterProvider,
            ObjectProvider<KafkaBatchInterceptor> batchInterceptorProvider,
            ObjectProvider<BatchMessageConverter> batchConverterProvider,
            ObjectProvider<CommonErrorHandler> errorHandlerProvider,
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            ObjectProvider<KafkaRebalanceListener> rebalanceListenerProvider,
            Environment environment) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (!(bean instanceof ConcurrentKafkaListenerContainerFactory<?, ?> factory)) {
                    return bean;
                }
                ContainerFactoryCollaborators collaborators = new ContainerFactoryCollaborators(
                        propertiesProvider.getIfAvailable(),
                        errorHandlerProvider,
                        observationRegistryProvider,
                        rebalanceListenerProvider,
                        environment);
                if (KafkaConstants.DEFAULT_CONTAINER_FACTORY.equals(beanName)) {
                    configureDefaultContainerFactory(
                            factory, interceptorProvider.getIfAvailable(), converterProvider.getIfAvailable(), collaborators);
                } else if (KafkaConstants.BATCH_CONTAINER_FACTORY.equals(beanName)) {
                    configureBatchContainerFactory(
                            factory, batchInterceptorProvider.getIfAvailable(), batchConverterProvider.getIfAvailable(), collaborators);
                }
                return bean;
            }
        };
    }

    private static void applyConfigurerOrConsumerFactory(
            ConcurrentKafkaListenerContainerFactory<Object, Object> factory,
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> kafkaConsumerFactory) {
        if (configurer != null) {
            configurer.configure(factory, kafkaConsumerFactory);
        } else {
            factory.setConsumerFactory(kafkaConsumerFactory);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void configureDefaultContainerFactory(
            ConcurrentKafkaListenerContainerFactory<?, ?> factory,
            KafkaRecordInterceptor interceptor,
            RecordMessageConverter converter,
            ContainerFactoryCollaborators collaborators) {
        ConcurrentKafkaListenerContainerFactory rawFactory = factory;
        if (interceptor != null) {
            rawFactory.setRecordInterceptor(interceptor);
        }
        rawFactory.setBatchInterceptor(null);
        if (converter != null) {
            rawFactory.setRecordMessageConverter(converter);
        }
        Integer concurrency = collaborators.properties() != null
                ? collaborators.properties().getConsumer().getConcurrency() : null;
        configureContainerFactoryCommon(
                rawFactory, false, ContainerProperties.AckMode.RECORD, concurrency, collaborators);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void configureBatchContainerFactory(
            ConcurrentKafkaListenerContainerFactory<?, ?> factory,
            KafkaBatchInterceptor batchInterceptor,
            BatchMessageConverter batchConverter,
            ContainerFactoryCollaborators collaborators) {
        ConcurrentKafkaListenerContainerFactory rawFactory = factory;
        if (batchInterceptor != null) {
            rawFactory.setBatchInterceptor(batchInterceptor);
        }
        rawFactory.setRecordInterceptor(null);
        if (batchConverter != null) {
            rawFactory.setBatchMessageConverter(batchConverter);
        }
        Integer concurrency = collaborators.properties() != null
                ? collaborators.properties().getConsumer().getBatchConcurrency() : null;
        configureContainerFactoryCommon(
                rawFactory, true, ContainerProperties.AckMode.BATCH, concurrency, collaborators);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void configureContainerFactoryCommon(
            ConcurrentKafkaListenerContainerFactory rawFactory,
            boolean batchListener,
            ContainerProperties.AckMode ackMode,
            Integer concurrency,
            ContainerFactoryCollaborators collaborators) {
        rawFactory.setBatchListener(batchListener);
        if (concurrency != null) {
            rawFactory.setConcurrency(concurrency);
        }
        if (collaborators.properties() != null) {
            rawFactory.getContainerProperties().setShutdownTimeout(
                    collaborators.properties().getConsumer().getShutdownTimeout().toMillis());
        }
        CommonErrorHandler errorHandler = collaborators.errorHandlerProvider().getIfAvailable();
        if (errorHandler != null) {
            rawFactory.setCommonErrorHandler(errorHandler);
        }
        rawFactory.getContainerProperties().setAckMode(ackMode);
        applyVirtualThreadIfEnabled(rawFactory, collaborators.environment());
        applyObservationIfPresent(rawFactory, collaborators.observationRegistryProvider());
        applyRebalanceListenerIfPresent(rawFactory, collaborators.rebalanceListenerProvider());
    }

    private record ContainerFactoryCollaborators(
            AtengKafkaProperties properties,
            ObjectProvider<CommonErrorHandler> errorHandlerProvider,
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            ObjectProvider<KafkaRebalanceListener> rebalanceListenerProvider,
            Environment environment) {
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

    private static void applyRebalanceListenerIfPresent(
            ConcurrentKafkaListenerContainerFactory<?, ?> factory,
            ObjectProvider<KafkaRebalanceListener> rebalanceListenerProvider) {
        if (factory == null || rebalanceListenerProvider == null) {
            return;
        }
        List<KafkaRebalanceListener> listeners = rebalanceListenerProvider.orderedStream().toList();
        if (listeners.isEmpty()) {
            return;
        }
        factory.getContainerProperties().setConsumerRebalanceListener(new ConsumerAwareRebalanceListener() {
            @Override
            public void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
                for (KafkaRebalanceListener listener : listeners) {
                    try {
                        listener.onPartitionsAssigned(consumer, partitions);
                    } catch (Throwable t) {
                        log.error("[MQ-REBALANCE] 执行 onPartitionsAssigned 监听钩子异常: {}", t.getMessage(), t);
                    }
                }
            }

            @Override
            public void onPartitionsRevokedBeforeCommit(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
                for (KafkaRebalanceListener listener : listeners) {
                    try {
                        listener.onPartitionsRevoked(consumer, partitions);
                    } catch (Throwable t) {
                        log.error("[MQ-REBALANCE] 执行 onPartitionsRevoked 监听钩子异常: {}", t.getMessage(), t);
                    }
                }
            }

            @Override
            public void onPartitionsLost(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
                for (KafkaRebalanceListener listener : listeners) {
                    try {
                        listener.onPartitionsLost(consumer, partitions);
                    } catch (Throwable t) {
                        log.error("[MQ-REBALANCE] 执行 onPartitionsLost 监听钩子异常: {}", t.getMessage(), t);
                    }
                }
            }
        });
        log.info("[ateng-boot-kafka] Kafka 监听容器工厂已挂载 {} 个 KafkaRebalanceListener 扩展点", listeners.size());
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
