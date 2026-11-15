package io.github.atengk.boot.kafka.autoconfigure;

import io.github.atengk.boot.kafka.constant.KafkaConstants;
import io.github.atengk.boot.kafka.executor.KafkaRecordExecutor;
import io.github.atengk.boot.kafka.interceptor.KafkaBatchInterceptor;
import io.github.atengk.boot.kafka.interceptor.KafkaRecordInterceptor;
import io.github.atengk.boot.kafka.manager.KafkaOffsetManager;
import io.github.atengk.boot.kafka.properties.AtengKafkaProperties;
import io.github.atengk.boot.kafka.spi.KafkaAdminClientFactory;
import io.github.atengk.boot.kafka.template.KafkaDeadLetterReplayTemplate;
import io.github.atengk.boot.kafka.template.KafkaReliableTemplate;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.converter.BatchMessageConverter;
import org.springframework.kafka.support.converter.RecordMessageConverter;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Kafka 自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("AtengKafkaAutoConfiguration 自动装配单元测试")
class AtengKafkaAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengKafkaAutoConfiguration.class));

    @Test
    @DisplayName("默认配置下成功自动装配配置类、定制器、拦截器、转换器、错误处理器与执行器")
    void testDefaultAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengKafkaProperties.class);
            AtengKafkaProperties properties = context.getBean(AtengKafkaProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getProducer().getSendTimeout().toSeconds()).isEqualTo(5);
            assertThat(properties.getProducer().getMaxPayloadSize().toMegabytes()).isEqualTo(1);

            // 生产与消费定制器
            assertThat(context).hasSingleBean(DefaultKafkaProducerFactoryCustomizer.class);
            assertThat(context.getBean(DefaultKafkaProducerFactoryCustomizer.class))
                    .isInstanceOf(AtengKafkaProducerFactoryCustomizer.class);
            assertThat(context).hasSingleBean(DefaultKafkaConsumerFactoryCustomizer.class);
            assertThat(context.getBean(DefaultKafkaConsumerFactoryCustomizer.class))
                    .isInstanceOf(AtengKafkaConsumerFactoryCustomizer.class);

            // 拦截器与批量执行器
            assertThat(context).hasSingleBean(KafkaRecordInterceptor.class);
            assertThat(context).hasSingleBean(KafkaBatchInterceptor.class);
            assertThat(context).hasSingleBean(KafkaRecordExecutor.class);

            // 消息转换器
            assertThat(context).hasSingleBean(RecordMessageConverter.class);
            assertThat(context).hasSingleBean(BatchMessageConverter.class);

            // 错误处理器
            assertThat(context).hasSingleBean(CommonErrorHandler.class);
        });
    }

    @Test
    @DisplayName("存在 ConsumerFactory 时成功装配单条与批量双容器工厂并生效不同 AckMode、并发度与优雅停机")
    void testDualContainerFactoriesAutoConfiguration() {
        contextRunner
                .withUserConfiguration(MockKafkaConsumerConfiguration.class)
                .run(context -> {
                    assertThat(context).hasBean(KafkaConstants.DEFAULT_CONTAINER_FACTORY);
                    assertThat(context).hasBean(KafkaConstants.BATCH_CONTAINER_FACTORY);

                    ConcurrentKafkaListenerContainerFactory<?, ?> singleFactory =
                            context.getBean(KafkaConstants.DEFAULT_CONTAINER_FACTORY, ConcurrentKafkaListenerContainerFactory.class);
                    ConcurrentKafkaListenerContainerFactory<?, ?> batchFactory =
                            context.getBean(KafkaConstants.BATCH_CONTAINER_FACTORY, ConcurrentKafkaListenerContainerFactory.class);

                    // 单条容器工厂校验
                    assertThat(singleFactory.isBatchListener()).isFalse();
                    assertThat(singleFactory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
                    assertThat(singleFactory.getContainerProperties().getShutdownTimeout()).isEqualTo(10000L);

                    // 批量容器工厂校验
                    assertThat(batchFactory.isBatchListener()).isTrue();
                    assertThat(batchFactory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.BATCH);
                    assertThat(batchFactory.getContainerProperties().getShutdownTimeout()).isEqualTo(10000L);
                });
    }

    @Test
    @DisplayName("存在 KafkaTemplate Bean 时成功自动装配 KafkaReliableTemplate 与 ConsumerRecordRecoverer")
    void testKafkaReliableTemplateAndRecovererAutoConfiguration() {
        contextRunner
                .withUserConfiguration(MockKafkaTemplateConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(KafkaReliableTemplate.class);
                    assertThat(context).hasSingleBean(ConsumerRecordRecoverer.class);
                });
    }

    @Test
    @DisplayName("配置 spring.threads.virtual.enabled=true 时适配虚拟线程执行器")
    void testVirtualThreadTaskExecutorAdaptation() {
        contextRunner
                .withPropertyValues("spring.threads.virtual.enabled=true")
                .withUserConfiguration(MockKafkaConsumerConfiguration.class)
                .run(context -> {
                    ConcurrentKafkaListenerContainerFactory<?, ?> singleFactory =
                            context.getBean(KafkaConstants.DEFAULT_CONTAINER_FACTORY, ConcurrentKafkaListenerContainerFactory.class);
                    boolean isVirtualAvailable;
                    try {
                        isVirtualAvailable = Thread.class.getMethod("ofVirtual") != null;
                    } catch (Throwable ex) {
                        isVirtualAvailable = false;
                    }
                    if (isVirtualAvailable) {
                        // JDK 21+ 环境自动接入虚拟线程执行器
                        assertThat(singleFactory.getContainerProperties().getListenerTaskExecutor()).isNotNull();
                    } else {
                        // JDK 17 生产基线环境安全降级回退
                        assertThat(singleFactory.getContainerProperties().getListenerTaskExecutor()).isNull();
                    }
                });
    }

    @Test
    @DisplayName("配置 ateng.boot.kafka.auto-create-topics=false 时后置处理 KafkaAdmin 禁用自动创建")
    void testKafkaAdminAutoCreateTopicsDisabled() {
        contextRunner
                .withPropertyValues("ateng.boot.kafka.auto-create-topics=false")
                .withUserConfiguration(MockKafkaAdminConfiguration.class)
                .run(context -> {
                    KafkaAdmin kafkaAdmin = context.getBean(KafkaAdmin.class);
                    java.lang.reflect.Field field = KafkaAdmin.class.getDeclaredField("autoCreate");
                    field.setAccessible(true);
                    boolean autoCreate = (boolean) field.get(kafkaAdmin);
                    assertThat(autoCreate).isFalse();
                });
    }

    @Test
    @DisplayName("配置 ateng.boot.kafka.enabled=false 时禁用自动装配")
    void testDisabledAutoConfiguration() {
        contextRunner
                .withPropertyValues("ateng.boot.kafka.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengKafkaProperties.class);
                    assertThat(context).doesNotHaveBean(AtengKafkaAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(DefaultKafkaProducerFactoryCustomizer.class);
                    assertThat(context).doesNotHaveBean(DefaultKafkaConsumerFactoryCustomizer.class);
                    assertThat(context).doesNotHaveBean(KafkaRecordInterceptor.class);
                    assertThat(context).doesNotHaveBean(KafkaBatchInterceptor.class);
                    assertThat(context).doesNotHaveBean(KafkaRecordExecutor.class);
                    assertThat(context).doesNotHaveBean(CommonErrorHandler.class);
                    assertThat(context).doesNotHaveBean(KafkaReliableTemplate.class);
                });
    }

    @Test
    @DisplayName("存在 ObservationRegistry 时双容器工厂自动开启 Observation 指标收集")
    void testObservationEnabledWhenRegistryPresent() {
        contextRunner
                .withUserConfiguration(MockKafkaConsumerConfiguration.class, MockObservationConfiguration.class)
                .run(context -> {
                    ConcurrentKafkaListenerContainerFactory<?, ?> singleFactory =
                            context.getBean(KafkaConstants.DEFAULT_CONTAINER_FACTORY, ConcurrentKafkaListenerContainerFactory.class);
                    ConcurrentKafkaListenerContainerFactory<?, ?> batchFactory =
                            context.getBean(KafkaConstants.BATCH_CONTAINER_FACTORY, ConcurrentKafkaListenerContainerFactory.class);

                    assertThat(singleFactory.getContainerProperties().isObservationEnabled()).isTrue();
                    assertThat(batchFactory.getContainerProperties().isObservationEnabled()).isTrue();
                });
    }

    @Test
    @DisplayName("后置处理器标配 @ConditionalOnMissingBean 支持下游平滑接管")
    void testPostProcessorsConditionalOnMissingBean() {
        contextRunner
                .withUserConfiguration(MockKafkaConsumerConfiguration.class)
                .run(context -> {
                    assertThat(context).hasBean("kafkaListenerContainerFactoryPostProcessor");
                    assertThat(context).hasBean("kafkaAdminPostProcessor");
                });
    }

    @Test
    @DisplayName("后置处理器守卫 batchKafkaListenerContainerFactory 维持 AckMode.BATCH 与 batchListener=true")
    void testPostProcessorPreservesBatchAckMode() {
        contextRunner
                .withUserConfiguration(MockKafkaConsumerConfiguration.class)
                .run(context -> {
                    ConcurrentKafkaListenerContainerFactory<?, ?> batchFactory =
                            context.getBean(KafkaConstants.BATCH_CONTAINER_FACTORY, ConcurrentKafkaListenerContainerFactory.class);
                    batchFactory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
                    batchFactory.setBatchListener(false);

                    org.springframework.beans.factory.config.BeanPostProcessor pp =
                            context.getBean("kafkaListenerContainerFactoryPostProcessor",
                                    org.springframework.beans.factory.config.BeanPostProcessor.class);
                    pp.postProcessAfterInitialization(batchFactory, KafkaConstants.BATCH_CONTAINER_FACTORY);

                    assertThat(batchFactory.isBatchListener()).isTrue();
                    assertThat(batchFactory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.BATCH);
                });
    }

    @Test
    @DisplayName("自定义 KafkaAdminClientFactory SPI Bean 自动注入 KafkaOffsetManager 与 KafkaDeadLetterReplayTemplate")
    void testCustomKafkaAdminClientFactoryWiring() {
        AdminClient mockAdminClient = mock(AdminClient.class);
        org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult offsetsResult =
                mock(org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult.class);
        org.mockito.Mockito.when(offsetsResult.partitionsToOffsetAndMetadata())
                .thenReturn(org.apache.kafka.common.KafkaFuture.completedFuture(java.util.Collections.emptyMap()));
        org.mockito.Mockito.when(mockAdminClient.listConsumerGroupOffsets("g1")).thenReturn(offsetsResult);

        org.apache.kafka.clients.admin.DescribeTopicsResult describeTopicsResult =
                mock(org.apache.kafka.clients.admin.DescribeTopicsResult.class);
        org.apache.kafka.clients.admin.TopicDescription emptyDescription =
                new org.apache.kafka.clients.admin.TopicDescription("orders.DLT", false, java.util.Collections.emptyList());
        org.mockito.Mockito.when(describeTopicsResult.values())
                .thenReturn(java.util.Collections.singletonMap(
                        "orders.DLT", org.apache.kafka.common.KafkaFuture.completedFuture(emptyDescription)));
        org.mockito.Mockito.when(mockAdminClient.describeTopics(java.util.Collections.singletonList("orders.DLT")))
                .thenReturn(describeTopicsResult);

        contextRunner
                .withUserConfiguration(MockKafkaConsumerConfiguration.class, MockKafkaTemplateConfiguration.class, MockKafkaAdminConfiguration.class)
                .withBean(KafkaAdminClientFactory.class, () -> props -> mockAdminClient)
                .run(context -> {
                    assertThat(context).hasSingleBean(KafkaOffsetManager.class);
                    assertThat(context).hasSingleBean(KafkaDeadLetterReplayTemplate.class);
                    KafkaOffsetManager offsetManager = context.getBean(KafkaOffsetManager.class);
                    java.util.Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets =
                            offsetManager.getConsumerGroupOffsets("g1");
                    assertThat(offsets).isEmpty();

                    KafkaDeadLetterReplayTemplate replayTemplate = context.getBean(KafkaDeadLetterReplayTemplate.class);
                    replayTemplate.purge("orders.DLT");
                    org.mockito.Mockito.verify(mockAdminClient)
                            .describeTopics(java.util.Collections.singletonList("orders.DLT"));
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class MockObservationConfiguration {
        @Bean
        public io.micrometer.observation.ObservationRegistry observationRegistry() {
            return io.micrometer.observation.ObservationRegistry.create();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MockKafkaTemplateConfiguration {
        @Bean
        @SuppressWarnings("unchecked")
        public KafkaTemplate<?, ?> kafkaTemplate() {
            return mock(KafkaTemplate.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MockKafkaConsumerConfiguration {
        @Bean
        @SuppressWarnings("unchecked")
        public ConsumerFactory<Object, Object> kafkaConsumerFactory() {
            return mock(ConsumerFactory.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MockKafkaAdminConfiguration {
        @Bean
        public KafkaAdmin kafkaAdmin() {
            return new KafkaAdmin(new HashMap<>());
        }
    }
}
