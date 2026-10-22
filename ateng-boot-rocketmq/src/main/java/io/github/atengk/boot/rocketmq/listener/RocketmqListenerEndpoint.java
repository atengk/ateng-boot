package io.github.atengk.boot.rocketmq.listener;

import lombok.Builder;
import lombok.Getter;

import java.lang.reflect.Method;

/**
 * RocketMQ 声明式监听端点元数据定义
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Getter
@Builder
public class RocketmqListenerEndpoint {

    private final Object targetBean;
    private final Method method;
    private final String topic;
    private final String consumerGroup;
    private final String tag;
    private final String sql92;
    private final int consumptionThreadCount;
    private final int maxCacheMessageCount;
    private final RocketmqListenerInvoker invoker;
}
