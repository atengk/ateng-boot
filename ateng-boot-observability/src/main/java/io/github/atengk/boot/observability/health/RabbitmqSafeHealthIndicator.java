package io.github.atengk.boot.observability.health;

import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * RabbitMQ 消息队列受管安全健康探针
 * <p>
 * 探查 RabbitMQ 物理连接与 Broker 服务版本信息，
 * 继承 {@link AbstractSafeHealthIndicator} 实施异步超时切断与状态降级防卫，
 * 在消息代理异常或网络假死时快速切断并发布降级事件，防止 Actuator 线程池耗尽。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Slf4j
public class RabbitmqSafeHealthIndicator extends AbstractSafeHealthIndicator {

    private final ObjectProvider<RabbitTemplate> rabbitTemplateProvider;
    private final ObjectProvider<ConnectionFactory> connectionFactoryProvider;

    public RabbitmqSafeHealthIndicator(
            ObjectProvider<RabbitTemplate> rabbitTemplateProvider,
            ObjectProvider<ConnectionFactory> connectionFactoryProvider) {
        this(rabbitTemplateProvider, connectionFactoryProvider, (Duration) null, null);
    }

    public RabbitmqSafeHealthIndicator(
            ObjectProvider<RabbitTemplate> rabbitTemplateProvider,
            ObjectProvider<ConnectionFactory> connectionFactoryProvider,
            ObservabilityProperties properties) {
        this(rabbitTemplateProvider, connectionFactoryProvider, properties, null);
    }

    public RabbitmqSafeHealthIndicator(
            ObjectProvider<RabbitTemplate> rabbitTemplateProvider,
            ObjectProvider<ConnectionFactory> connectionFactoryProvider,
            ObservabilityProperties properties,
            Executor executor) {
        super(properties, executor);
        this.rabbitTemplateProvider = rabbitTemplateProvider;
        this.connectionFactoryProvider = connectionFactoryProvider;
    }

    public RabbitmqSafeHealthIndicator(
            ObjectProvider<RabbitTemplate> rabbitTemplateProvider,
            ObjectProvider<ConnectionFactory> connectionFactoryProvider,
            Duration timeout,
            Executor executor) {
        super(timeout, executor);
        this.rabbitTemplateProvider = rabbitTemplateProvider;
        this.connectionFactoryProvider = connectionFactoryProvider;
    }

    @Override
    protected String getComponentName() {
        return "rabbitmq";
    }

    @Override
    protected void doCheck(Health.Builder builder) throws Exception {
        RabbitTemplate template = rabbitTemplateProvider != null ? rabbitTemplateProvider.getIfAvailable() : null;
        ConnectionFactory connectionFactory = connectionFactoryProvider != null ? connectionFactoryProvider.getIfAvailable() : null;

        if (template == null && connectionFactory == null) {
            builder.unknown().withDetail("message", "未检测到已装配的 RabbitMQ 客户端实例");
            return;
        }

        if (template != null) {
            Object version = template.execute(channel -> {
                com.rabbitmq.client.Connection conn = channel.getConnection();
                if (conn != null && conn.getServerProperties() != null) {
                    return conn.getServerProperties().get("version");
                }
                return "CONNECTED";
            });
            builder.up().withDetail("version", version != null ? version.toString() : "CONNECTED");
            return;
        }

        Connection conn = connectionFactory.createConnection();
        try {
            com.rabbitmq.client.Connection delegate = conn.getDelegate();
            Object version = (delegate != null && delegate.getServerProperties() != null)
                    ? delegate.getServerProperties().get("version")
                    : "CONNECTED";
            builder.up().withDetail("version", version != null ? version.toString() : "CONNECTED");
        } finally {
            conn.close();
        }
    }
}
