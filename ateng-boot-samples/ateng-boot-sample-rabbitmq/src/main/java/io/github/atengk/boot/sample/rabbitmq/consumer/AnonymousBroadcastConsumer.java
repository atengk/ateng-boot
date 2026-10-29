package io.github.atengk.boot.sample.rabbitmq.consumer;

import io.github.atengk.boot.sample.rabbitmq.config.RabbitScenarioTopologyConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 动态匿名临时排他广播消费者组件
 * 演示在 Fanout 临时广播订阅场景中使用原生 @Queue 声明动态匿名、排他、自动删除临时队列接收并处理广播通知
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Slf4j
@Component
public class AnonymousBroadcastConsumer {

    private final List<String> receivedBroadcasts = new CopyOnWriteArrayList<>();

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue,
            exchange = @Exchange(value = RabbitScenarioTopologyConfig.EXCHANGE_BROADCAST_FANOUT, type = ExchangeTypes.FANOUT)
    ))
    public void onBroadcast(String message) {
        log.info("[CONSUMER-BROADCAST] 动态匿名队列收到广播消息: message={}", message);
        this.receivedBroadcasts.add(message);
    }

    public List<String> getReceivedBroadcasts() {
        return this.receivedBroadcasts;
    }

    public void clear() {
        this.receivedBroadcasts.clear();
    }
}
