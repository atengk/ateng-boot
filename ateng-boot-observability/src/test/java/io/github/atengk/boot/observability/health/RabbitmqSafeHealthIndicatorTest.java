package io.github.atengk.boot.observability.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RabbitmqSafeHealthIndicator 单元测试
 *
 * @author Ateng
 * @since 2026-10-30
 */
@DisplayName("RabbitmqSafeHealthIndicator RabbitMQ 安全健康探针单元测试")
class RabbitmqSafeHealthIndicatorTest {

    @Test
    @DisplayName("未装配任何 RabbitMQ 客户端时，返回 UNKNOWN 状态")
    void testNoRabbitmqConfigured() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        RabbitmqSafeHealthIndicator indicator = new RabbitmqSafeHealthIndicator(
                beanFactory.getBeanProvider(RabbitTemplate.class),
                beanFactory.getBeanProvider(ConnectionFactory.class)
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("message", "未检测到已装配的 RabbitMQ 客户端实例");
    }

    @Test
    @DisplayName("RabbitTemplate 正常执行回调获取到版本号，返回 UP 状态")
    void testRabbitTemplateUp() {
        RabbitTemplate mockTemplate = mock(RabbitTemplate.class);
        when(mockTemplate.execute(any())).thenReturn("3.12.4");

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("rabbitTemplate", mockTemplate);

        RabbitmqSafeHealthIndicator indicator = new RabbitmqSafeHealthIndicator(
                beanFactory.getBeanProvider(RabbitTemplate.class),
                beanFactory.getBeanProvider(ConnectionFactory.class),
                Duration.ofSeconds(1),
                null
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("version", "3.12.4");
    }

    @Test
    @DisplayName("仅装配 ConnectionFactory 时正常创建连接并获取版本，返回 UP 状态")
    void testConnectionFactoryUp() {
        ConnectionFactory mockFactory = mock(ConnectionFactory.class);
        Connection mockConnection = mock(Connection.class);
        com.rabbitmq.client.Connection mockDelegate = mock(com.rabbitmq.client.Connection.class);

        when(mockFactory.createConnection()).thenReturn(mockConnection);
        when(mockConnection.getDelegate()).thenReturn(mockDelegate);
        when(mockDelegate.getServerProperties()).thenReturn(Map.of("version", "3.11.0"));

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("connectionFactory", mockFactory);

        RabbitmqSafeHealthIndicator indicator = new RabbitmqSafeHealthIndicator(
                beanFactory.getBeanProvider(RabbitTemplate.class),
                beanFactory.getBeanProvider(ConnectionFactory.class),
                Duration.ofSeconds(1),
                null
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("version", "3.11.0");
    }

    @Test
    @DisplayName("连接失败抛出异常时，返回 DOWN 状态")
    void testRabbitmqDown() {
        RabbitTemplate mockTemplate = mock(RabbitTemplate.class);
        when(mockTemplate.execute(any())).thenThrow(new AmqpConnectException(new Exception("Connection refused")));

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("rabbitTemplate", mockTemplate);

        RabbitmqSafeHealthIndicator indicator = new RabbitmqSafeHealthIndicator(
                beanFactory.getBeanProvider(RabbitTemplate.class),
                beanFactory.getBeanProvider(ConnectionFactory.class)
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("reason");
    }

    @Test
    @DisplayName("探测超时熔断：切断降级为 UNKNOWN 状态")
    void testRabbitmqTimeoutDegradation() {
        RabbitTemplate mockTemplate = mock(RabbitTemplate.class);
        when(mockTemplate.execute(any())).thenAnswer(invocation -> {
            Thread.sleep(500);
            return "3.12.0";
        });

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("rabbitTemplate", mockTemplate);

        RabbitmqSafeHealthIndicator indicator = new RabbitmqSafeHealthIndicator(
                beanFactory.getBeanProvider(RabbitTemplate.class),
                beanFactory.getBeanProvider(ConnectionFactory.class),
                Duration.ofMillis(80),
                null
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("status", "TIMEOUT");
    }
}
