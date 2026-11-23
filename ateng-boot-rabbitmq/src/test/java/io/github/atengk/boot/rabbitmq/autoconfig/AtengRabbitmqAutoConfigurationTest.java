package io.github.atengk.boot.rabbitmq.autoconfig;

import io.github.atengk.boot.rabbitmq.executor.RabbitBatchExecutor;
import io.github.atengk.boot.rabbitmq.interceptor.RabbitListenerGlobalAdvice;
import io.github.atengk.boot.rabbitmq.manager.RabbitContainerManager;
import io.github.atengk.boot.rabbitmq.template.RabbitDeadLetterReplayTemplate;
import io.github.atengk.boot.rabbitmq.template.RabbitReliableTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.ConditionalRejectingErrorHandler;
import org.springframework.amqp.rabbit.listener.FatalExceptionStrategy;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * RabbitMQ 自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("AtengRabbitmqAutoConfiguration 自动装配单元测试")
class AtengRabbitmqAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengRabbitmqAutoConfiguration.class));

    @Test
    @DisplayName("默认配置下成功自动装配核心 Bean")
    void testDefaultAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(MessageConverter.class);
            assertThat(context.getBean(MessageConverter.class)).isInstanceOf(Jackson2JsonMessageConverter.class);

            assertThat(context).hasSingleBean(RabbitTemplateCustomizer.class);
            assertThat(context).hasSingleBean(ContainerCustomizer.class);
            assertThat(context).hasSingleBean(RabbitTemplate.ReturnsCallback.class);
            assertThat(context).hasSingleBean(FatalExceptionStrategy.class);
            assertThat(context).hasSingleBean(ConditionalRejectingErrorHandler.class);
            assertThat(context).hasSingleBean(RabbitListenerGlobalAdvice.class);
            assertThat(context).hasSingleBean(RabbitContainerManager.class);
            assertThat(context).hasSingleBean(RabbitBatchExecutor.class);
        });
    }

    @Test
    @DisplayName("存在 RabbitTemplate 时成功装配 RabbitReliableTemplate 与 RabbitDeadLetterReplayTemplate 门面")
    void testRabbitReliableTemplateAutoConfiguration() {
        contextRunner
                .withUserConfiguration(MockRabbitTemplateConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(RabbitReliableTemplate.class);
                    RabbitReliableTemplate template = context.getBean(RabbitReliableTemplate.class);
                    assertThat(template.getRabbitTemplate()).isNotNull();

                    assertThat(context).hasSingleBean(RabbitDeadLetterReplayTemplate.class);
                    RabbitDeadLetterReplayTemplate dlqTemplate = context.getBean(RabbitDeadLetterReplayTemplate.class);
                    assertThat(dlqTemplate.getRabbitTemplate()).isNotNull();
                });
    }

    @Test
    @DisplayName("用户自定义 RabbitContainerManager 时平滑无缝覆盖 (@ConditionalOnMissingBean)")
    void testUserCustomRabbitContainerManagerOverride() {
        RabbitContainerManager customManager = new RabbitContainerManager((RabbitListenerEndpointRegistry) null);
        contextRunner
                .withBean("customRabbitContainerManager", RabbitContainerManager.class, () -> customManager)
                .run(context -> {
                    assertThat(context).hasSingleBean(RabbitContainerManager.class);
                    assertThat(context.getBean(RabbitContainerManager.class)).isSameAs(customManager);
                });
    }

    @Test
    @DisplayName("用户自定义 RabbitBatchExecutor 时平滑无缝覆盖 (@ConditionalOnMissingBean)")
    void testUserCustomRabbitBatchExecutorOverride() {
        RabbitBatchExecutor customExecutor = new RabbitBatchExecutor(new SimpleMessageConverter());
        contextRunner
                .withBean("customRabbitBatchExecutor", RabbitBatchExecutor.class, () -> customExecutor)
                .run(context -> {
                    assertThat(context).hasSingleBean(RabbitBatchExecutor.class);
                    assertThat(context.getBean(RabbitBatchExecutor.class)).isSameAs(customExecutor);
                });
    }

    @Test
    @DisplayName("配置 ateng.boot.rabbitmq.enabled=false 时安全停用装配")
    void testDisabledAutoConfiguration() {
        contextRunner
                .withPropertyValues("ateng.boot.rabbitmq.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MessageConverter.class);
                    assertThat(context).doesNotHaveBean(RabbitTemplateCustomizer.class);
                    assertThat(context).doesNotHaveBean(ContainerCustomizer.class);
                    assertThat(context).doesNotHaveBean(RabbitTemplate.ReturnsCallback.class);
                    assertThat(context).doesNotHaveBean(RabbitReliableTemplate.class);
                    assertThat(context).doesNotHaveBean(RabbitDeadLetterReplayTemplate.class);
                    assertThat(context).doesNotHaveBean(FatalExceptionStrategy.class);
                    assertThat(context).doesNotHaveBean(ConditionalRejectingErrorHandler.class);
                    assertThat(context).doesNotHaveBean(RabbitListenerGlobalAdvice.class);
                    assertThat(context).doesNotHaveBean(RabbitContainerManager.class);
                    assertThat(context).doesNotHaveBean(RabbitBatchExecutor.class);
                });
    }

    @Test
    @DisplayName("用户自定义 MessageConverter 时平滑无缝覆盖 (@ConditionalOnMissingBean)")
    void testUserCustomMessageConverterOverride() {
        contextRunner
                .withUserConfiguration(CustomConverterConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(MessageConverter.class);
                    assertThat(context.getBean(MessageConverter.class)).isInstanceOf(SimpleMessageConverter.class);
                });
    }

    @Test
    @DisplayName("topology.auto-declare=false 时禁用 RabbitAdmin 启动自动声明 (autoStartup=false)")
    void testTopologyAutoDeclareDisabled() {
        contextRunner
                .withUserConfiguration(MockRabbitAdminConfiguration.class)
                .withPropertyValues("ateng.boot.rabbitmq.topology.auto-declare=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(RabbitAdmin.class);
                    RabbitAdmin admin = context.getBean(RabbitAdmin.class);
                    assertThat(admin.isAutoStartup()).isFalse();
                });
    }

    @Test
    @DisplayName("默认配置下 RabbitAdmin 保持自动声明启用 (autoStartup=true)")
    void testTopologyAutoDeclareDefaultEnabled() {
        contextRunner
                .withUserConfiguration(MockRabbitAdminConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(RabbitAdmin.class);
                    RabbitAdmin admin = context.getBean(RabbitAdmin.class);
                    assertThat(admin.isAutoStartup()).isTrue();
                });
    }

    @Test
    @DisplayName("用户自定义 BeanPostProcessor 时平滑无缝覆盖 (@ConditionalOnMissingBean)")
    void testUserCustomLifecycleBeanPostProcessorOverride() {
        contextRunner
                .withUserConfiguration(CustomLifecycleBeanPostProcessorConfiguration.class)
                .run(context -> {
                    assertThat(context).hasBean("atengRabbitLifecycleBeanPostProcessor");
                    assertThat(context.getBean("atengRabbitLifecycleBeanPostProcessor"))
                            .isNotInstanceOf(AtengRabbitmqAutoConfiguration.AtengRabbitLifecycleBeanPostProcessor.class);
                });
    }

    @Test
    @DisplayName("开启虚拟线程配置时自适应装配或平滑降级")
    void testVirtualThreadsAutoConfiguration() {
        contextRunner
                .withPropertyValues("spring.threads.virtual.enabled=true")
                .run(context -> {
                    boolean isJdk21OrLater = Runtime.version().feature() >= 21;
                    if (isJdk21OrLater) {
                        assertThat(context).hasBean("atengRabbitVirtualTaskExecutor");
                        assertThat(context).hasBean("atengRabbitVirtualThreadCustomizer");
                    } else {
                        assertThat(context).doesNotHaveBean("atengRabbitVirtualTaskExecutor");
                        assertThat(context).doesNotHaveBean("atengRabbitVirtualThreadCustomizer");
                    }
                });
    }

    @Test
    @DisplayName("consumer.batch.enabled=true 时后置增强 SimpleRabbitListenerContainerFactory 开启批量消费与超时")
    void testConsumerBatchEnabledContainerFactoryCustomization() {
        contextRunner
                .withUserConfiguration(MockContainerFactoryConfiguration.class)
                .withPropertyValues(
                        "ateng.boot.rabbitmq.consumer.batch.enabled=true",
                        "ateng.boot.rabbitmq.consumer.batch.size=80",
                        "ateng.boot.rabbitmq.consumer.batch.max-wait-time=800ms"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory.class);
                    org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory factory =
                            context.getBean(org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory.class);

                    Boolean consumerBatchEnabled = (Boolean) org.springframework.test.util.ReflectionTestUtils.getField(factory, "consumerBatchEnabled");
                    Boolean batchListener = (Boolean) org.springframework.test.util.ReflectionTestUtils.getField(factory, "batchListener");
                    Integer batchSize = (Integer) org.springframework.test.util.ReflectionTestUtils.getField(factory, "batchSize");
                    Long batchReceiveTimeout = (Long) org.springframework.test.util.ReflectionTestUtils.getField(factory, "batchReceiveTimeout");

                    assertThat(consumerBatchEnabled).isTrue();
                    assertThat(batchListener).isTrue();
                    assertThat(batchSize).isEqualTo(80);
                    assertThat(batchReceiveTimeout).isEqualTo(800L);
                });
    }

    @Test
    @DisplayName("batchRabbitListenerContainerFactory 仅保留单一路径外层重试 + 内层全局拦截链顺序且无重复 Advice")
    void testBatchFactoryAdviceChainOrderWithoutDuplicates() {
        contextRunner
                .withUserConfiguration(MockConnectionFactoryConfiguration.class)
                .withPropertyValues("ateng.boot.rabbitmq.consumer.retry.enabled=true")
                .run(context -> {
                    org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory batchFactory =
                            context.getBean("batchRabbitListenerContainerFactory",
                                    org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory.class);
                    org.aopalliance.aop.Advice[] chain = batchFactory.getAdviceChain();
                    assertThat(chain).hasSize(2);
                    assertThat(chain[0]).isInstanceOf(org.springframework.retry.interceptor.RetryOperationsInterceptor.class);
                    assertThat(chain[1]).isInstanceOf(RabbitListenerGlobalAdvice.class);

                    Object operations = org.springframework.test.util.ReflectionTestUtils.getField(chain[0], "retryOperations");
                    Object recoverer = org.springframework.test.util.ReflectionTestUtils.getField(chain[0], "recoverer");
                    assertThat(operations).isNotNull();
                    assertThat(recoverer).isInstanceOf(org.springframework.retry.interceptor.MethodInvocationRecoverer.class);

                    ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                            org.slf4j.LoggerFactory.getLogger(AtengRabbitmqAutoConfiguration.class);
                    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                            new ch.qos.logback.core.read.ListAppender<>();
                    appender.start();
                    logger.addAppender(appender);
                    try {
                        org.springframework.amqp.core.MessageProperties props =
                                new org.springframework.amqp.core.MessageProperties();
                        props.setReceivedExchange("ex.biz");
                        props.setReceivedRoutingKey("rk.biz");
                        props.setConsumerQueue("q.biz");
                        props.setHeader("X-Biz-Key", "biz-99");
                        props.setHeader("X-Trace-Id", "trace-99");
                        org.springframework.amqp.core.Message msg =
                                new org.springframework.amqp.core.Message("bad".getBytes(), props);

                        org.springframework.retry.interceptor.MethodInvocationRecoverer<?> methodRecoverer =
                                (org.springframework.retry.interceptor.MethodInvocationRecoverer<?>) recoverer;
                        org.assertj.core.api.Assertions.assertThatThrownBy(
                                () -> methodRecoverer.recover(new Object[]{null, msg}, new IllegalStateException("重试耗尽")))
                                .isInstanceOf(org.springframework.amqp.rabbit.support.ListenerExecutionFailedException.class)
                                .hasCauseInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);

                        assertThat(appender.list)
                                .anySatisfy(event -> {
                                    assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
                                    assertThat(event.getFormattedMessage())
                                            .contains("[MQ-CONSUME-RETRY-EXHAUSTED]")
                                            .contains("biz-99")
                                            .contains("trace-99");
                                });
                    } finally {
                        logger.detachAppender(appender);
                    }
                });

        contextRunner
                .withUserConfiguration(MockConnectionFactoryConfiguration.class)
                .withPropertyValues("ateng.boot.rabbitmq.consumer.retry.enabled=false")
                .run(context -> {
                    org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory batchFactory =
                            context.getBean("batchRabbitListenerContainerFactory",
                                    org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory.class);
                    org.aopalliance.aop.Advice[] chain = batchFactory.getAdviceChain();
                    assertThat(chain).hasSize(1);
                    assertThat(chain[0]).isInstanceOf(RabbitListenerGlobalAdvice.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class MockConnectionFactoryConfiguration {
        @Bean
        public ConnectionFactory connectionFactory() {
            return mock(ConnectionFactory.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MockContainerFactoryConfiguration {
        @Bean
        public org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory() {
            return new org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomLifecycleBeanPostProcessorConfiguration {
        @Bean
        public org.springframework.beans.factory.config.BeanPostProcessor atengRabbitLifecycleBeanPostProcessor() {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {};
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MockRabbitAdminConfiguration {
        @Bean
        public RabbitAdmin rabbitAdmin() {
            ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
            return new RabbitAdmin(connectionFactory);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomConverterConfiguration {
        @Bean
        public MessageConverter customMessageConverter() {
            return new SimpleMessageConverter();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MockRabbitTemplateConfiguration {
        @Bean
        public RabbitTemplate rabbitTemplate() {
            return mock(RabbitTemplate.class);
        }
    }
}
