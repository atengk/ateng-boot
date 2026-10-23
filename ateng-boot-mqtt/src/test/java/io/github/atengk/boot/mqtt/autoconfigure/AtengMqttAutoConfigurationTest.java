package io.github.atengk.boot.mqtt.autoconfigure;

import io.github.atengk.boot.mqtt.registry.NamedMqttClientRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AtengMqttAutoConfiguration 自动装配测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
class AtengMqttAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengMqttAutoConfiguration.class));

    @Test
    @DisplayName("测试缺省自动装配：启用且无外部配置时正常注册 NamedMqttClientRegistry")
    void testDefaultAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(NamedMqttClientRegistry.class);
        });
    }

    @Test
    @DisplayName("测试禁用开关：ateng.boot.mqtt.enabled=false 时不注册 NamedMqttClientRegistry")
    void testDisabledAutoConfiguration() {
        contextRunner.withPropertyValues("ateng.boot.mqtt.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(NamedMqttClientRegistry.class);
                });
    }
}
