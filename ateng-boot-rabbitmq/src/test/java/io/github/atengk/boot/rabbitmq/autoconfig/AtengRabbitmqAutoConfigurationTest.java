package io.github.atengk.boot.rabbitmq.autoconfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Configuration(proxyBeanMethods = false)
    static class CustomConverterConfiguration {
        @Bean
        public MessageConverter customMessageConverter() {
            return new SimpleMessageConverter();
        }
    }
}
