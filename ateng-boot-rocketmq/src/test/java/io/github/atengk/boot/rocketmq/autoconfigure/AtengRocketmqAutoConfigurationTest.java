package io.github.atengk.boot.rocketmq.autoconfigure;

import io.github.atengk.boot.core.autoconfigure.AtengAsyncAutoConfiguration;
import io.github.atengk.boot.rocketmq.annotation.RocketmqListener;
import io.github.atengk.boot.rocketmq.checker.RocketmqTransactionChecker;
import io.github.atengk.boot.rocketmq.consumer.PushConsumerFactory;
import io.github.atengk.boot.rocketmq.consumer.SimpleConsumerFactory;
import io.github.atengk.boot.rocketmq.container.RocketmqContainerManager;
import io.github.atengk.boot.rocketmq.listener.RocketmqConsumerContainer;
import io.github.atengk.boot.rocketmq.properties.AtengRocketmqProperties;
import io.github.atengk.boot.rocketmq.spi.RocketmqLocalTransactionChecker;
import io.github.atengk.boot.rocketmq.template.RocketmqDeadLetterReplayTemplate;
import io.github.atengk.boot.rocketmq.template.RocketmqSimpleConsumerTemplate;
import io.github.atengk.boot.rocketmq.template.RocketmqTemplate;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.TransactionChecker;
import org.apache.rocketmq.client.apis.producer.TransactionResolution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
            assertThat(context).doesNotHaveBean(RocketmqDeadLetterReplayTemplate.class);

            AtengRocketmqProperties properties = context.getBean(AtengRocketmqProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getEndpoints()).isNull();
            assertThat(properties.getRequestTimeout().toSeconds()).isEqualTo(3);
            assertThat(properties.getProducer().getMaxPayloadSize().toMegabytes()).isEqualTo(4);
            assertThat(properties.getConsumer().getConsumptionThreadCount()).isEqualTo(20);
            assertThat(properties.getDeadLetter().getMaxReplayAttempts()).isEqualTo(3);
            assertThat(properties.getDeadLetter().getDefaultBatchSize()).isEqualTo(32);
            assertThat(properties.getDeadLetter().getMaxBatchSize()).isEqualTo(100);
            assertThat(properties.getDeadLetter().getInvisibleDuration().toSeconds()).isEqualTo(30);
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
                    assertThat(context).hasSingleBean(RocketmqContainerManager.class);

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
    @DisplayName("存在 Producer Bean 时成功自动装配 RocketmqTemplate 与 RocketmqDeadLetterReplayTemplate")
    void testRocketmqTemplateAutoConfigurationWithProducer() {
        contextRunner
                .withUserConfiguration(MockProducerConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(Producer.class);
                    assertThat(context).hasSingleBean(RocketmqTemplate.class);
                    assertThat(context).hasSingleBean(RocketmqDeadLetterReplayTemplate.class);

                    RocketmqTemplate template = context.getBean(RocketmqTemplate.class);
                    assertThat(template.getProducer()).isSameAs(context.getBean(Producer.class));

                    RocketmqDeadLetterReplayTemplate replayTemplate = context.getBean(RocketmqDeadLetterReplayTemplate.class);
                    assertThat(replayTemplate.getRocketmqTemplate()).isSameAs(template);
                    assertThat(replayTemplate.getSimpleConsumerTemplate()).isSameAs(context.getBean(RocketmqSimpleConsumerTemplate.class));
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

    @Test
    @DisplayName("停机阶段序列：PushConsumer 优先排空，其次 SimpleConsumer 关闭，最后 Producer 关闭")
    void testSmartLifecycleShutdownOrdering() throws Exception {
        PushConsumer mockPushConsumer = mock(PushConsumer.class);
        SimpleConsumer mockSimpleConsumer = mock(SimpleConsumer.class);
        Producer mockProducer = mock(Producer.class);

        PushConsumerFactory pushFactory = mock(PushConsumerFactory.class);
        when(pushFactory.createPushConsumer(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(mockPushConsumer);
        SimpleConsumerFactory simpleFactory = mock(SimpleConsumerFactory.class);
        when(simpleFactory.createSimpleConsumer(any(), any(), any(), any()))
                .thenReturn(mockSimpleConsumer);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(PushConsumerFactory.class, () -> pushFactory);
            context.registerBean(SimpleConsumerFactory.class, () -> simpleFactory);
            context.registerBean("rocketmqProducer", Producer.class, () -> mockProducer, bd -> bd.setDestroyMethodName("close"));
            context.registerBean(ClientConfiguration.class, () -> mock(ClientConfiguration.class));
            context.registerBean(OrderedShutdownListenerBean.class);
            context.register(ShutdownPropertiesImportConfig.class);
            context.refresh();

            RocketmqSimpleConsumerTemplate simpleTemplate = context.getBean(RocketmqSimpleConsumerTemplate.class);
            RocketmqConsumerContainer container = context.getBean(RocketmqConsumerContainer.class);
            assertThat(simpleTemplate.getPhase()).isLessThan(container.getPhase());

            simpleTemplate.receive("order_topic", "order_simple_group", 1, Duration.ofSeconds(10));
            context.close();

            InOrder inOrder = inOrder(mockPushConsumer, mockSimpleConsumer, mockProducer);
            inOrder.verify(mockPushConsumer).close();
            inOrder.verify(mockSimpleConsumer).close();
            inOrder.verify(mockProducer).close();
        }
    }

    @Test
    @DisplayName("自动装配排序与受管线程池注入：RocketmqConsumerContainer 共用 Core taskExecutor")
    void testCoreTaskExecutorWiredIntoContainerAndTemplate() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AtengAsyncAutoConfiguration.class, AtengRocketmqAutoConfiguration.class))
                .withPropertyValues("ateng.boot.rocketmq.endpoints=127.0.0.1:8081")
                .run(context -> {
                    assertThat(context).hasBean("taskExecutor");
                    Executor coreExecutor = context.getBean("taskExecutor", Executor.class);
                    RocketmqConsumerContainer container = context.getBean(RocketmqConsumerContainer.class);
                    assertThat(container.getTaskExecutor()).isSameAs(coreExecutor);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AtengRocketmqProperties.class)
    @Import(AtengRocketmqAutoConfiguration.class)
    static class ShutdownPropertiesImportConfig {
    }

    static class OrderedShutdownListenerBean {
        @RocketmqListener(id = "shutdown_order_listener", topic = "order_topic", consumerGroup = "order_push_group")
        public void onMessage(String body) {
        }
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
