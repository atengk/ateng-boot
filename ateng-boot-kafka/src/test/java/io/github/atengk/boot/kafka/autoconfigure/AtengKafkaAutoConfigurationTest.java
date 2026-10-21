package io.github.atengk.boot.kafka.autoconfigure;

import io.github.atengk.boot.kafka.properties.AtengKafkaProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

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
    @DisplayName("默认配置下成功自动装配 AtengKafkaProperties")
    void testDefaultAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengKafkaProperties.class);
            AtengKafkaProperties properties = context.getBean(AtengKafkaProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getProducer().getSendTimeout().toSeconds()).isEqualTo(5);
            assertThat(properties.getProducer().getMaxPayloadSize().toMegabytes()).isEqualTo(1);
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
                });
    }
}
