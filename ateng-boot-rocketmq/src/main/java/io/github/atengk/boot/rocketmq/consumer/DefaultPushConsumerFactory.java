package io.github.atengk.boot.rocketmq.consumer;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.consumer.PushConsumerBuilder;

import java.util.Collections;

/**
 * 默认基于 RocketMQ 5.x 官方 SDK 的 PushConsumer 工厂实现
 *
 * @author Ateng
 * @since 2026-10-22
 */
public class DefaultPushConsumerFactory implements PushConsumerFactory {

    private final ClientServiceProvider provider = ClientServiceProvider.loadService();

    @Override
    public PushConsumer createPushConsumer(
            ClientConfiguration clientConfiguration,
            String consumerGroup,
            String topic,
            FilterExpression filterExpression,
            MessageListener messageListener,
            int threadCount,
            int maxCacheMessageCount) throws ClientException {
        PushConsumerBuilder builder = provider.newPushConsumerBuilder()
                .setClientConfiguration(clientConfiguration)
                .setConsumerGroup(consumerGroup)
                .setSubscriptionExpressions(Collections.singletonMap(topic, filterExpression))
                .setMessageListener(messageListener);

        if (threadCount > 0) {
            builder.setConsumptionThreadCount(threadCount);
        }
        if (maxCacheMessageCount > 0) {
            builder.setMaxCacheMessageCount(maxCacheMessageCount);
        }

        return builder.build();
    }
}
