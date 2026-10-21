package io.github.atengk.boot.sample.rabbitmq.config;

import io.github.atengk.boot.rabbitmq.builder.RabbitTopologyBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * RabbitMQ 演示场景拓扑配置
 * 演示使用 RabbitTopologyBuilder 声明 Direct、Topic、Quorum 队列、单活独占消费者、死信拓扑 (DLX+DLQ) 与插件延迟交换机
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Configuration(proxyBeanMethods = false)
public class RabbitScenarioTopologyConfig {

    public static final String EXCHANGE_ORDER_DIRECT = "sample.order.direct";
    public static final String QUEUE_ORDER_CREATE = "sample.order.create.queue";
    public static final String ROUTING_KEY_ORDER_CREATE = "order.create";

    public static final String QUEUE_ORDER_IDEMPOTENT = "sample.order.idempotent.queue";
    public static final String ROUTING_KEY_ORDER_IDEMPOTENT = "order.idempotent";

    public static final String EXCHANGE_LOG_TOPIC = "sample.log.topic";
    public static final String QUEUE_LOG_ERROR = "sample.log.error.queue";
    public static final String ROUTING_KEY_LOG_ERROR = "log.error.#";

    public static final String EXCHANGE_ORDER_DELAY = "sample.order.delay.direct";
    public static final String QUEUE_ORDER_DELAY = "sample.order.delay.queue";
    public static final String ROUTING_KEY_ORDER_TIMEOUT = "order.timeout";

    public static final String EXCHANGE_BIZ_DIRECT = "sample.biz.direct";
    public static final String QUEUE_BIZ_MAIN = "sample.biz.queue";
    public static final String ROUTING_KEY_BIZ = "biz.work";
    public static final String EXCHANGE_BIZ_DLX = "sample.biz.dlx";
    public static final String QUEUE_BIZ_DLQ = "sample.biz.dlq";
    public static final String ROUTING_KEY_BIZ_DEAD = "biz.dead";

    /**
     * 场景 A & B: Direct 交换机、Quorum 队列、单活消费者与幂等测试队列拓扑
     */
    @Bean
    public Declarables orderDirectTopology() {
        return RabbitTopologyBuilder.direct(EXCHANGE_ORDER_DIRECT)
                .queue(QUEUE_ORDER_CREATE)
                .quorum()
                .singleActiveConsumer()
                .routingKey(ROUTING_KEY_ORDER_CREATE)
                .andQueue(QUEUE_ORDER_IDEMPOTENT)
                .routingKey(ROUTING_KEY_ORDER_IDEMPOTENT)
                .build();
    }

    /**
     * 场景 A: Topic 通配符交换机拓扑 (支持层级路由如 log.error.trade)
     */
    @Bean
    public Declarables logTopicTopology() {
        return RabbitTopologyBuilder.topic(EXCHANGE_LOG_TOPIC)
                .queue(QUEUE_LOG_ERROR)
                .routingKey(ROUTING_KEY_LOG_ERROR)
                .build();
    }

    /**
     * 场景 C: 基于 rabbitmq_delayed_message_exchange 插件的延迟队列拓扑
     */
    @Bean
    public Declarables orderDelayTopology() {
        return RabbitTopologyBuilder.delayed(EXCHANGE_ORDER_DELAY)
                .queue(QUEUE_ORDER_DELAY)
                .routingKey(ROUTING_KEY_ORDER_TIMEOUT)
                .build();
    }

    /**
     * 场景 D: 业务队列挂载死信拓扑 (DLX + DLQ + 死信终点隔离)
     */
    @Bean
    public Declarables bizDeadLetterTopology() {
        return RabbitTopologyBuilder.direct(EXCHANGE_BIZ_DIRECT)
                .queue(QUEUE_BIZ_MAIN)
                .routingKey(ROUTING_KEY_BIZ)
                .deadLetter(EXCHANGE_BIZ_DLX, QUEUE_BIZ_DLQ, ROUTING_KEY_BIZ_DEAD)
                .build();
    }

    /**
     * 场景 C (备选/原生双模): 纯原生 TTL+DLX 延迟暂存队列
     */
    @Bean
    public Declarables nativeTtlDelayQueue() {
        return RabbitTopologyBuilder.ttlDelayQueue(
                "sample.order.native-delay.queue",
                Duration.ofMinutes(15),
                EXCHANGE_ORDER_DIRECT,
                ROUTING_KEY_ORDER_CREATE
        );
    }
}
