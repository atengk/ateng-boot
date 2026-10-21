package io.github.atengk.boot.kafka.autoconfigure;

import io.github.atengk.boot.kafka.properties.AtengKafkaProperties;
import io.github.atengk.boot.kafka.template.KafkaReliableTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

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
    @DisplayName("默认配置下成功自动装配 AtengKafkaProperties 与 DefaultKafkaProducerFactoryCustomizer")
    void testDefaultAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengKafkaProperties.class);
            AtengKafkaProperties properties = context.getBean(AtengKafkaProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getProducer().getSendTimeout().toSeconds()).isEqualTo(5);
            assertThat(properties.getProducer().getMaxPayloadSize().toMegabytes()).isEqualTo(1);

            assertThat(context).hasSingleBean(DefaultKafkaProducerFactoryCustomizer.class);
            assertThat(context.getBean(DefaultKafkaProducerFactoryCustomizer.class))
                    .isInstanceOf(AtengKafkaProducerFactoryCustomizer.class);
        });
    }

    @Test
    @DisplayName("存在 KafkaTemplate Bean 时成功自动装配 KafkaReliableTemplate 门面")
    void testKafkaReliableTemplateAutoConfiguration() {
        contextRunner
                .withUserConfiguration(MockKafkaTemplateConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(KafkaReliableTemplate.class);
                    KafkaReliableTemplate template = context.getBean(KafkaReliableTemplate.class);
                    assertThat(template).isNotNull();
                    assertThat(template.getKafkaTemplate()).isNotNull();
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
}
