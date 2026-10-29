package io.github.atengk.boot.rabbitmq.autoconfig;

import io.github.atengk.boot.rabbitmq.interceptor.RabbitListenerGlobalAdvice;
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
