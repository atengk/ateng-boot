package io.github.atengk.boot.rocketmq.autoconfigure;

import io.github.atengk.boot.rocketmq.properties.AtengRocketmqProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RocketMQ 自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("AtengRocketmqAutoConfiguration 自动装配单元测试")
class AtengRocketmqAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengRocketmqAutoConfiguration.class));

    @Test
    @DisplayName("默认未配置 endpoints 时成功装配属性类且平滑静默跳过物理客户端初始化")
    void testDefaultAutoConfigurationWithoutEndpoints() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengRocketmqAutoConfiguration.class);
            assertThat(context).hasSingleBean(AtengRocketmqProperties.class);

            AtengRocketmqProperties properties = context.getBean(AtengRocketmqProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getEndpoints()).isNull();
            assertThat(properties.getRequestTimeout().toSeconds()).isEqualTo(3);
            assertThat(properties.getProducer().getMaxPayloadSize().toMegabytes()).isEqualTo(4);
            assertThat(properties.getConsumer().getConsumptionThreadCount()).isEqualTo(20);
        });
    }

    @Test
    @DisplayName("显式配置 endpoints 时成功激活装配并读取属性")
    void testAutoConfigurationWithEndpoints() {
        contextRunner
                .withPropertyValues("ateng.boot.rocketmq.endpoints=127.0.0.1:8081",
                        "ateng.boot.rocketmq.request-timeout=5s")
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengRocketmqAutoConfiguration.class);
                    assertThat(context).hasSingleBean(AtengRocketmqProperties.class);

                    AtengRocketmqProperties properties = context.getBean(AtengRocketmqProperties.class);
                    assertThat(properties.getEndpoints()).isEqualTo("127.0.0.1:8081");
                    assertThat(properties.getRequestTimeout().toSeconds()).isEqualTo(5);
                });
    }

    @Test
    @DisplayName("配置 ateng.boot.rocketmq.enabled=false 时装配类被平滑跳过")
    void testDisabledAutoConfiguration() {
        contextRunner
                .withPropertyValues("ateng.boot.rocketmq.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengRocketmqAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(AtengRocketmqProperties.class);
                });
    }
}
