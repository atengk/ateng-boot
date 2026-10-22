package io.github.atengk.boot.observability.health;

import io.github.atengk.boot.rocketmq.listener.RocketmqConsumerContainer;
import io.github.atengk.boot.rocketmq.listener.RocketmqListenerEndpoint;
import io.github.atengk.boot.rocketmq.template.RocketmqTemplate;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RocketmqHealthIndicator 消息探针单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("RocketmqHealthIndicator 消息健康探针单元测试")
class RocketmqHealthIndicatorTest {

    @Test
    @DisplayName("未装配任何 RocketMQ 客户端时，返回 UNKNOWN 状态")
    void testNoRocketmqConfigured() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        RocketmqHealthIndicator indicator = new RocketmqHealthIndicator(
                beanFactory.getBeanProvider(RocketmqTemplate.class),
                beanFactory.getBeanProvider(RocketmqConsumerContainer.class)
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("message", "未检测到已装配的 RocketMQ 客户端实例");
    }

    @Test
    @DisplayName("发送端 Producer 与消费容器 Container 均正常运行，返回 UP 状态")
    void testProducerAndConsumerRunningUp() {
        RocketmqTemplate mockTemplate = mock(RocketmqTemplate.class);
        Producer mockProducer = mock(Producer.class);
        when(mockTemplate.getProducer()).thenReturn(mockProducer);

        RocketmqConsumerContainer mockContainer = mock(RocketmqConsumerContainer.class);
        when(mockContainer.isRunning()).thenReturn(true);
        when(mockContainer.getActiveConsumers()).thenReturn(List.of(mock(PushConsumer.class)));
        when(mockContainer.getEndpoints()).thenReturn(List.of(mock(RocketmqListenerEndpoint.class)));

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("rocketmqTemplate", mockTemplate);
        beanFactory.addBean("rocketmqConsumerContainer", mockContainer);

        RocketmqHealthIndicator indicator = new RocketmqHealthIndicator(
                beanFactory.getBeanProvider(RocketmqTemplate.class),
                beanFactory.getBeanProvider(RocketmqConsumerContainer.class),
                Duration.ofSeconds(1),
                null
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsKey("producer");
        assertThat(health.getDetails()).containsKey("consumerContainer");

        @SuppressWarnings("unchecked")
        Map<String, Object> producerDetails = (Map<String, Object>) health.getDetails().get("producer");
        assertThat(producerDetails).containsEntry("status", "UP");

        @SuppressWarnings("unchecked")
        Map<String, Object> consumerDetails = (Map<String, Object>) health.getDetails().get("consumerContainer");
        assertThat(consumerDetails).containsEntry("status", "UP");
        assertThat(consumerDetails).containsEntry("running", true);
        assertThat(consumerDetails).containsEntry("activeConsumers", 1);
        assertThat(consumerDetails).containsEntry("endpoints", 1);
    }

    @Test
    @DisplayName("消费端配置了监听端点但容器未启动时，判定为 DOWN 状态")
    void testConsumerContainerNotRunningDown() {
        RocketmqConsumerContainer mockContainer = mock(RocketmqConsumerContainer.class);
        when(mockContainer.isRunning()).thenReturn(false);
        when(mockContainer.getActiveConsumers()).thenReturn(List.of());
        when(mockContainer.getEndpoints()).thenReturn(List.of(mock(RocketmqListenerEndpoint.class)));

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("rocketmqConsumerContainer", mockContainer);

        RocketmqHealthIndicator indicator = new RocketmqHealthIndicator(
                beanFactory.getBeanProvider(RocketmqTemplate.class),
                beanFactory.getBeanProvider(RocketmqConsumerContainer.class)
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        @SuppressWarnings("unchecked")
        Map<String, Object> consumerDetails = (Map<String, Object>) health.getDetails().get("consumerContainer");
        assertThat(consumerDetails).containsEntry("status", "DOWN");
        assertThat(consumerDetails).containsEntry("running", false);
    }

    @Test
    @DisplayName("发送端 Producer 实例为 null 时，判定为 DOWN 状态")
    void testProducerNullDown() {
        RocketmqTemplate mockTemplate = mock(RocketmqTemplate.class);
        when(mockTemplate.getProducer()).thenReturn(null);

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("rocketmqTemplate", mockTemplate);

        RocketmqHealthIndicator indicator = new RocketmqHealthIndicator(
                beanFactory.getBeanProvider(RocketmqTemplate.class),
                beanFactory.getBeanProvider(RocketmqConsumerContainer.class)
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        @SuppressWarnings("unchecked")
        Map<String, Object> producerDetails = (Map<String, Object>) health.getDetails().get("producer");
        assertThat(producerDetails).containsEntry("status", "DOWN");
        assertThat(producerDetails).containsKey("error");
    }
}
