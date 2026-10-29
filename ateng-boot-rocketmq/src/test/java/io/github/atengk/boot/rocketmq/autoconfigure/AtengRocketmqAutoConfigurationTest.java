package io.github.atengk.boot.rocketmq.autoconfigure;

import io.github.atengk.boot.rocketmq.checker.RocketmqTransactionChecker;
import io.github.atengk.boot.rocketmq.consumer.PushConsumerFactory;
import io.github.atengk.boot.rocketmq.consumer.SimpleConsumerFactory;
import io.github.atengk.boot.rocketmq.listener.RocketmqConsumerContainer;
import io.github.atengk.boot.rocketmq.properties.AtengRocketmqProperties;
import io.github.atengk.boot.rocketmq.spi.RocketmqLocalTransactionChecker;
import io.github.atengk.boot.rocketmq.template.RocketmqSimpleConsumerTemplate;
import io.github.atengk.boot.rocketmq.template.RocketmqTemplate;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.TransactionChecker;
import org.apache.rocketmq.client.apis.producer.TransactionResolution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
            assertThat(context).doesNotHaveBean(ClientConfiguration.class);
            assertThat(context).doesNotHaveBean(Producer.class);
            assertThat(context).doesNotHaveBean(RocketmqTemplate.class);

            AtengRocketmqProperties properties = context.getBean(AtengRocketmqProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getEndpoints()).isNull();
            assertThat(properties.getRequestTimeout().toSeconds()).isEqualTo(3);
            assertThat(properties.getProducer().getMaxPayloadSize().toMegabytes()).isEqualTo(4);
            assertThat(properties.getConsumer().getConsumptionThreadCount()).isEqualTo(20);
        });
    }

    @Test
    @DisplayName("显式配置 endpoints 时成功激活 ClientConfiguration 与 RocketmqTransactionChecker")
    void testAutoConfigurationWithEndpoints() {
        contextRunner
                .withPropertyValues("ateng.boot.rocketmq.endpoints=127.0.0.1:8081",
                        "ateng.boot.rocketmq.request-timeout=5s")
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengRocketmqAutoConfiguration.class);
                    assertThat(context).hasSingleBean(AtengRocketmqProperties.class);
                    assertThat(context).hasSingleBean(ClientConfiguration.class);
                    assertThat(context).hasSingleBean(RocketmqTransactionChecker.class);
                    assertThat(context).hasSingleBean(PushConsumerFactory.class);
                    assertThat(context).hasSingleBean(SimpleConsumerFactory.class);
                    assertThat(context).hasSingleBean(RocketmqConsumerContainer.class);
                    assertThat(context).hasSingleBean(RocketmqSimpleConsumerTemplate.class);

                    AtengRocketmqProperties properties = context.getBean(AtengRocketmqProperties.class);
                    assertThat(properties.getEndpoints()).isEqualTo("127.0.0.1:8081");
                    assertThat(properties.getRequestTimeout().toSeconds()).isEqualTo(5);
                });
    }

    @Test
    @DisplayName("自定义 TransactionChecker 存在时默认 RocketmqTransactionChecker 平滑避让")
    void testCustomTransactionCheckerOverride() {
        contextRunner
                .withPropertyValues("ateng.boot.rocketmq.endpoints=127.0.0.1:8081")
                .withUserConfiguration(CustomTransactionCheckerConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(TransactionChecker.class);
                    assertThat(context).doesNotHaveBean(RocketmqTransactionChecker.class);
                    assertThat(context.getBean(TransactionChecker.class))
                            .isSameAs(context.getBean(CustomTransactionCheckerConfiguration.class).customChecker);
                });
    }

    @Test
    @DisplayName("容器中定义的 RocketmqLocalTransactionChecker 被 RocketmqTransactionChecker 自动收集管理")
    void testTransactionCheckerCollectsLocalCheckers() {
        contextRunner
                .withPropertyValues("ateng.boot.rocketmq.endpoints=127.0.0.1:8081")
                .withUserConfiguration(LocalCheckersConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(RocketmqTransactionChecker.class);
                    RocketmqTransactionChecker checker = context.getBean(RocketmqTransactionChecker.class);
                    assertThat(checker.getCheckers()).hasSize(1);
                    assertThat(checker.getCheckers().get(0).getTopic()).isEqualTo("order_topic");
                });
    }

    @Test
    @DisplayName("存在 Producer Bean 时成功自动装配 RocketmqTemplate")
    void testRocketmqTemplateAutoConfigurationWithProducer() {
        contextRunner
                .withUserConfiguration(MockProducerConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(Producer.class);
                    assertThat(context).hasSingleBean(RocketmqTemplate.class);

                    RocketmqTemplate template = context.getBean(RocketmqTemplate.class);
                    assertThat(template.getProducer()).isSameAs(context.getBean(Producer.class));
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

    @Configuration(proxyBeanMethods = false)
    static class MockProducerConfiguration {
        @Bean
        public Producer customProducer() {
            return mock(Producer.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomTransactionCheckerConfiguration {
        final TransactionChecker customChecker = mock(TransactionChecker.class);

        @Bean
        public TransactionChecker transactionChecker() {
            return customChecker;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LocalCheckersConfiguration {
        @Bean
        public RocketmqLocalTransactionChecker orderTransactionChecker() {
            return new RocketmqLocalTransactionChecker() {
                @Override
                public String getTopic() {
                    return "order_topic";
                }

                @Override
                public TransactionResolution check(MessageView messageView) {
                    return TransactionResolution.COMMIT;
                }
            };
        }
    }
}
