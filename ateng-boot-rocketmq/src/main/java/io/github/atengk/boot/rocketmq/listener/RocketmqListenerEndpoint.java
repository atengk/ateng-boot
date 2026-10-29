package io.github.atengk.boot.rocketmq.listener;

import lombok.Builder;
import lombok.Getter;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;

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

    private final String id;
    private final Object targetBean;
    private final Method method;
    private final String topic;
    private final String consumerGroup;
    private final String tag;
    private final String sql92;
    private final int consumptionThreadCount;
    private final int maxCacheMessageCount;
    private final RocketmqListenerInvoker invoker;

    private volatile PushConsumer pushConsumer;
    private volatile boolean running;

    public void setPushConsumer(PushConsumer pushConsumer) {
        this.pushConsumer = pushConsumer;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    /**
     * 判断当前端点是否处于暂停流控状态
     *
     * @return 若当前端点已暂停返回 true
     */
    public boolean isPaused() {
        return this.invoker != null && this.invoker.isPaused();
    }
}
