package io.github.atengk.boot.kafka.autoconfigure;

import io.github.atengk.boot.kafka.constant.KafkaConstants;
import io.github.atengk.boot.kafka.executor.KafkaRecordExecutor;
import io.github.atengk.boot.kafka.interceptor.KafkaBatchInterceptor;
import io.github.atengk.boot.kafka.interceptor.KafkaRecordInterceptor;
import io.github.atengk.boot.kafka.properties.AtengKafkaProperties;
import io.github.atengk.boot.kafka.template.KafkaReliableTemplate;
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
                    // 只要当前运行环境支持 Thread.ofVirtual()，listenerTaskExecutor 就会被设置为虚拟线程执行器
                    assertThat(singleFactory.getContainerProperties().getListenerTaskExecutor()).isNotNull();
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
