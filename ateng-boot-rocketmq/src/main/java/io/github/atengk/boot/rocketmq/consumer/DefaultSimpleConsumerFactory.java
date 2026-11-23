package io.github.atengk.boot.rocketmq.consumer;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumerBuilder;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * RocketMQ 5.x 官方 SDK 默认 SimpleConsumer 构造工厂
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class DefaultSimpleConsumerFactory implements SimpleConsumerFactory {

    private final ClientServiceProvider provider = ClientServiceProvider.loadService();

    @Override
    public SimpleConsumer createSimpleConsumer(
            ClientConfiguration clientConfiguration,
            String consumerGroup,
            Map<String, FilterExpression> subscriptionExpressions,
            Duration awaitDuration) throws ClientException {
        Objects.requireNonNull(clientConfiguration, "clientConfiguration 不能为空");
        Objects.requireNonNull(consumerGroup, "consumerGroup 不能为空");

        SimpleConsumerBuilder builder = provider.newSimpleConsumerBuilder()
                .setClientConfiguration(clientConfiguration)
                .setConsumerGroup(consumerGroup.trim());

        if (subscriptionExpressions != null && !subscriptionExpressions.isEmpty()) {
            builder.setSubscriptionExpressions(subscriptionExpressions);
        }
        if (awaitDuration != null && !awaitDuration.isZero() && !awaitDuration.isNegative()) {
            builder.setAwaitDuration(awaitDuration);
        }
        return builder.build();
    }
}
