package io.github.atengk.boot.rocketmq.consumer;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;

/**
 * RocketMQ 5.x PushConsumer 构建工厂 SPI
 *
 * <p>解耦底层官方 SDK 的静态 {@code ClientServiceProvider.loadService()} 强依赖，
 * 允许在单元测试或离线环境中无缝注入 TestDouble 或 Mock 实现，确保 100% 离线自洽运行。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@FunctionalInterface
public interface PushConsumerFactory {

    /**
     * 构建并启动受管 PushConsumer 实例
     *
     * @param clientConfiguration  客户端连接配置
     * @param consumerGroup        消费者组名称
     * @param topic                订阅主题
     * @param filterExpression     过滤表达式 (Tag 或 SQL92)
     * @param messageListener      消息监听消费处理器
     * @param threadCount          消费并发线程数（大于 0 时生效）
     * @param maxCacheMessageCount 最大缓存消息数（大于 0 时生效）
     * @return 已启动的 PushConsumer 实例
     * @throws ClientException 启动失败时抛出底层客户端异常
     */
    PushConsumer createPushConsumer(
            ClientConfiguration clientConfiguration,
            String consumerGroup,
            String topic,
            FilterExpression filterExpression,
            MessageListener messageListener,
            int threadCount,
            int maxCacheMessageCount) throws ClientException;
}
