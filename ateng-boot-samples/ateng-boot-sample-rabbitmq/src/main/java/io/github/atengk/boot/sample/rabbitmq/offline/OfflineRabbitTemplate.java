package io.github.atengk.boot.sample.rabbitmq.offline;

import com.rabbitmq.client.Channel;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.UncategorizedAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Objects;

/**
 * 离线内存仿真 RabbitTemplate，继承标准 RabbitTemplate 并透明保留转换器与定制器注入能力，将发送与信道执行委派至内存 Broker
 *
 * @author Ateng
 * @since 2026-11-15
 */
public class OfflineRabbitTemplate extends RabbitTemplate {

    private final OfflineAmqpBroker broker;

    public OfflineRabbitTemplate(ConnectionFactory connectionFactory, OfflineAmqpBroker broker) {
        super(Objects.requireNonNull(connectionFactory, "connectionFactory 不能为空"));
        this.broker = Objects.requireNonNull(broker, "broker 不能为空");
    }

    @Override
    public void setReturnsCallback(ReturnsCallback returnCallback) {
        super.setReturnsCallback(returnCallback);
        broker.setReturnsCallback(returnCallback);
    }

    @Override
    public void send(String exchange, String routingKey, Message message) throws AmqpException {
        broker.dispatch(exchange, routingKey, message, null);
    }

    @Override
    public void send(String exchange, String routingKey, Message message, CorrelationData correlationData)
            throws AmqpException {
        broker.dispatch(exchange, routingKey, message, correlationData);
    }

    @Override
    public <T> T execute(ChannelCallback<T> action) throws AmqpException {
        return invokeWithOfflineChannel(action);
    }

    private <T> T invokeWithOfflineChannel(ChannelCallback<T> action) {
        Objects.requireNonNull(action, "ChannelCallback 不能为空");
        Channel channel = OfflineAmqpProxyFactory.createChannel(broker);
        try {
            return action.doInRabbit(channel);
        } catch (AmqpException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new UncategorizedAmqpException("离线模拟 Channel 执行失败", ex);
        }
    }
}
