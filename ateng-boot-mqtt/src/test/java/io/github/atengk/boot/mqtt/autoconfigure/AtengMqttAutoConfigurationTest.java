package io.github.atengk.boot.mqtt.autoconfigure;

import io.github.atengk.boot.mqtt.actuator.MqttHealthIndicator;
import io.github.atengk.boot.mqtt.codec.CompositeMqttMessageConverter;
import io.github.atengk.boot.mqtt.defense.PayloadGuard;
import io.github.atengk.boot.mqtt.handler.MqttSendFailureHandler;
import io.github.atengk.boot.mqtt.listener.MqttConnectionEventDispatcher;
import io.github.atengk.boot.mqtt.listener.MqttContainerManager;
import io.github.atengk.boot.mqtt.listener.MqttListenerAnnotationBeanPostProcessor;
import io.github.atengk.boot.mqtt.listener.MqttListenerEndpointRegistry;
import io.github.atengk.boot.mqtt.metrics.MqttMetrics;
import io.github.atengk.boot.mqtt.registry.NamedMqttClientRegistry;
import io.github.atengk.boot.mqtt.requestreply.MqttRequestReplyManager;
import io.github.atengk.boot.mqtt.template.MqttDeadLetterReplayTemplate;
import io.github.atengk.boot.mqtt.template.MqttTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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
    @DisplayName("测试缺省自动装配：启用且无外部配置时正常注册所有核心基础设施与监听器容器组件")
    void testDefaultAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(NamedMqttClientRegistry.class);
            assertThat(context).hasSingleBean(PayloadGuard.class);
            assertThat(context).hasSingleBean(MqttSendFailureHandler.class);
            assertThat(context).hasSingleBean(CompositeMqttMessageConverter.class);
            assertThat(context).hasSingleBean(ThreadPoolTaskExecutor.class);
            assertThat(context).hasSingleBean(MqttRequestReplyManager.class);
            assertThat(context).hasSingleBean(MqttTemplate.class);
            assertThat(context).hasSingleBean(MqttDeadLetterReplayTemplate.class);
            assertThat(context).hasSingleBean(MqttListenerEndpointRegistry.class);
            assertThat(context).hasSingleBean(MqttContainerManager.class);
            assertThat(context).hasSingleBean(MqttConnectionEventDispatcher.class);
            assertThat(context).hasSingleBean(MqttListenerAnnotationBeanPostProcessor.class);
            assertThat(context).hasSingleBean(MqttHealthIndicator.class);
        });
    }

    @Test
    @DisplayName("测试禁用开关：ateng.boot.mqtt.enabled=false 时不注册 MQTT 相关组件")
    void testDisabledAutoConfiguration() {
        contextRunner.withPropertyValues("ateng.boot.mqtt.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(NamedMqttClientRegistry.class);
                    assertThat(context).doesNotHaveBean(PayloadGuard.class);
                    assertThat(context).doesNotHaveBean(MqttSendFailureHandler.class);
                    assertThat(context).doesNotHaveBean(MqttRequestReplyManager.class);
                    assertThat(context).doesNotHaveBean(MqttTemplate.class);
                    assertThat(context).doesNotHaveBean(MqttDeadLetterReplayTemplate.class);
                    assertThat(context).doesNotHaveBean(MqttListenerEndpointRegistry.class);
                    assertThat(context).doesNotHaveBean(MqttContainerManager.class);
                    assertThat(context).doesNotHaveBean(MqttConnectionEventDispatcher.class);
                    assertThat(context).doesNotHaveBean(MqttListenerAnnotationBeanPostProcessor.class);
                });
    }

    @Test
    @DisplayName("测试可观测性自动装配：存在 MeterRegistry 时注册 MqttMetrics，可通过配置禁用")
    void testObservabilityAutoConfiguration() {
        contextRunner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(MqttMetrics.class);
                    assertThat(context).hasSingleBean(MqttHealthIndicator.class);
                });

        contextRunner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues(
                        "management.metrics.enable.mqtt=false",
                        "management.health.mqtt.enabled=false"
                )
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MqttMetrics.class);
                    assertThat(context).doesNotHaveBean(MqttHealthIndicator.class);
                });
    }
}
