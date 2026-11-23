package io.github.atengk.boot.rocketmq.consumer;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;

import java.time.Duration;
import java.util.Map;

/**
 * RocketMQ 5.x SimpleConsumer 构建工厂 SPI
 *
 * <p>解耦底层官方 SDK 的静态 {@code ClientServiceProvider.loadService()} 强依赖，
 * 允许在单元测试或离线环境中无缝注入 TestDouble 或 Mock 实现，确保 100% 离线自洽运行。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@FunctionalInterface
public interface SimpleConsumerFactory {

    /**
     * 构建受管 SimpleConsumer 实例
     *
     * @param clientConfiguration     客户端连接配置
     * @param consumerGroup           消费者组名称
     * @param subscriptionExpressions 初始订阅关系映射 (Topic -> FilterExpression)
     * @param awaitDuration           长轮询最大等待时间
     * @return SimpleConsumer 实例
     * @throws ClientException 构建失败时抛出底层客户端异常
     */
    SimpleConsumer createSimpleConsumer(
            ClientConfiguration clientConfiguration,
            String consumerGroup,
            Map<String, FilterExpression> subscriptionExpressions,
            Duration awaitDuration) throws ClientException;
}
