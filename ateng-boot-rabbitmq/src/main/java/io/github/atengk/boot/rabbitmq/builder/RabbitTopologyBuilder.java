package io.github.atengk.boot.rabbitmq.builder;

import org.springframework.amqp.core.AbstractExchange;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * RabbitMQ 声明式流畅拓扑构建器 (Fluent API)
 * 支持 Direct、Topic、Fanout 及 Delayed 插件交换机链式拓扑声明，
 * 原生支持 Raft Quorum 高可用仲裁队列、单活独占消费者 (Single Active Consumer)、DLX/DLQ 死信终点隔离与 TTL+DLX 纯原生双模延迟队列
 *
 * @author Ateng
 * @since 2026-10-21
 */
public final class RabbitTopologyBuilder {

    private RabbitTopologyBuilder() {
    }

    /**
     * 声明 Direct 精准直连交换机拓扑
     *
     * @param exchangeName 交换机名称
     * @return 交换机拓扑构建器
     */
    public static ExchangeTopologyBuilder direct(String exchangeName) {
        Assert.hasText(exchangeName, "exchangeName 交换机名称不能为空");
        return new ExchangeTopologyBuilder(new DirectExchange(exchangeName, true, false));
    }

    /**
     * 声明 Topic 层次匹配通配符交换机拓扑
     *
     * @param exchangeName 交换机名称
     * @return 交换机拓扑构建器
     */
    public static ExchangeTopologyBuilder topic(String exchangeName) {
        Assert.hasText(exchangeName, "exchangeName 交换机名称不能为空");
        return new ExchangeTopologyBuilder(new TopicExchange(exchangeName, true, false));
    }

    /**
     * 声明 Fanout 广播广播交换机拓扑
     *
     * @param exchangeName 交换机名称
     * @return 交换机拓扑构建器
     */
    public static ExchangeTopologyBuilder fanout(String exchangeName) {
        Assert.hasText(exchangeName, "exchangeName 交换机名称不能为空");
        return new ExchangeTopologyBuilder(new FanoutExchange(exchangeName, true, false));
    }

    /**
     * 声明基于 rabbitmq_delayed_message_exchange 插件的延迟交换机拓扑 (默认底层直连 direct 路由)
     *
     * @param exchangeName 交换机名称
     * @return 交换机拓扑构建器
     */
    public static ExchangeTopologyBuilder delayed(String exchangeName) {
        return delayed(exchangeName, "direct");
    }

    /**
     * 声明基于 rabbitmq_delayed_message_exchange 插件的延迟交换机拓扑，显式指定底层匹配类型
     *
     * @param exchangeName 交换机名称
     * @param delayedType  底层匹配类型 (direct, topic, fanout)
     * @return 交换机拓扑构建器
     */
    public static ExchangeTopologyBuilder delayed(String exchangeName, String delayedType) {
        Assert.hasText(exchangeName, "exchangeName 交换机名称不能为空");
        String type = (delayedType != null && !delayedType.isBlank()) ? delayedType : "direct";
        Map<String, Object> args = new HashMap<>();
        args.put("x-delayed-type", type);
        CustomExchange exchange = new CustomExchange(exchangeName, "x-delayed-message", true, false, args);
        return new ExchangeTopologyBuilder(exchange);
    }

    /**
     * 声明纯原生 TTL+DLX 延迟暂存队列 (零插件依赖双模方案)
     * 消息投递至该队列等待 TTL 过期后，由 Broker 自动死信流转至目标交换机与目标路由键
     *
     * @param ttlQueueName     TTL 暂存队列名称
     * @param delay            延迟过期时间
     * @param targetExchange   过期死信流转目标交换机
     * @param targetRoutingKey 过期死信流转目标路由键
     * @return Spring Declarables 集合
     */
    public static Declarables ttlDelayQueue(
            String ttlQueueName,
            Duration delay,
            String targetExchange,
            String targetRoutingKey) {
        Assert.hasText(ttlQueueName, "ttlQueueName 不能为空");
        Assert.notNull(delay, "delay 延迟时间不能为空");
        Assert.isTrue(!delay.isNegative() && !delay.isZero(), "delay 延迟时间必须大于 0");
        Assert.hasText(targetExchange, "targetExchange 目标交换机不能为空");

        Map<String, Object> args = new HashMap<>();
        args.put("x-message-ttl", delay.toMillis());
        args.put("x-dead-letter-exchange", targetExchange);
        args.put("x-dead-letter-routing-key", targetRoutingKey != null ? targetRoutingKey : "");

        Queue queue = new Queue(ttlQueueName, true, false, false, args);
        return new Declarables(queue);
    }

    /**
     * 交换机级拓扑构建器
     */
    public static class ExchangeTopologyBuilder {
        private final Exchange exchange;

        public ExchangeTopologyBuilder(Exchange exchange) {
            this.exchange = Objects.requireNonNull(exchange, "exchange 不能为空");
        }

        /**
         * 设置备用交换机 (alternate-exchange)
         * 当主交换机无法将消息路由至任何绑定队列时，Broker 将自动把消息路由至此备用交换机进行兜底处理
         *
         * @param exchangeName 备用交换机名称
         * @return 交换机拓扑构建器
         */
        public ExchangeTopologyBuilder alternateExchange(String exchangeName) {
            Assert.hasText(exchangeName, "alternateExchange 备用交换机名称不能为空");
            Assert.isTrue(!this.exchange.getName().equals(exchangeName), "备用交换机不能与当前主交换机同名");
            if (this.exchange instanceof AbstractExchange abstractExchange) {
                abstractExchange.addArgument("alternate-exchange", exchangeName.trim());
            }
            return this;
        }

        /**
         * 声明绑定业务队列
         *
         * @param queueName 队列名称
         * @return 队列级拓扑构建器
         */
        public QueueStep queue(String queueName) {
            Assert.hasText(queueName, "queueName 队列名称不能为空");
            return new QueueStep(this.exchange, queueName);
        }

        /**
         * 声明绑定已初始化的队列实例 (如 Spring 受管的 AnonymousQueue 或自定义 Queue Bean)
         *
         * @param queue 队列实例
         * @return 队列级拓扑构建器
         */
        public QueueStep queue(Queue queue) {
            Assert.notNull(queue, "queue 不能为空");
            return new QueueStep(this.exchange, queue);
        }

        /**
         * 声明基于 Spring AMQP 的动态匿名排他自动删除队列 (AnonymousQueue)
         * 常用于 Fanout 广播或多实例临时订阅消费场景，连接断开后由 Broker 自动销毁
         *
         * @return 队列级拓扑构建器
         */
        public QueueStep anonymousQueue() {
            return new QueueStep(this.exchange, new AnonymousQueue());
        }

        /**
         * 声明命名独占排他、非持久、自动删除临时队列
         *
         * @param queueName 队列名称
         * @return 队列级拓扑构建器
         */
        public QueueStep exclusiveAutoDeleteQueue(String queueName) {
            Assert.hasText(queueName, "queueName 队列名称不能为空");
            Queue queue = new Queue(queueName, false, true, true);
            return new QueueStep(this.exchange, queue);
        }

        /**
         * 声明匿名独占排他、非持久、自动删除临时队列
         *
         * @return 队列级拓扑构建器
         */
        public QueueStep exclusiveAutoDeleteQueue() {
            return anonymousQueue();
        }

        /**
         * 构建仅包含交换机的 Declarables (常用于广播交换机或由消费者端独立声明队列与绑定的场景)
         *
         * @return Declarables 实例
         */
        public Declarables build() {
            return new Declarables(this.exchange);
        }
    }

    /**
     * 队列与绑定拓扑链式构建器
     */
    public static class QueueStep {
        private final Exchange exchange;
        private final List<QueueDefinition> queueDefinitions = new ArrayList<>();
        private QueueDefinition currentQueue;

        QueueStep(Exchange exchange, String queueName) {
            this.exchange = exchange;
            this.currentQueue = new QueueDefinition(queueName);
            this.queueDefinitions.add(this.currentQueue);
        }

        QueueStep(Exchange exchange, Queue queue) {
            this.exchange = exchange;
            this.currentQueue = new QueueDefinition(queue);
            this.queueDefinitions.add(this.currentQueue);
        }

        /**
         * 设置队列类型为现代高可用 Raft Quorum 仲裁队列 (面向 RabbitMQ 4.0 基线)
         */
        public QueueStep quorum() {
            this.currentQueue.arguments.put("x-queue-type", "quorum");
            this.currentQueue.durable = true;
            return this;
        }

        /**
         * 设置单活独占消费者模式 (x-single-active-consumer: true)，保障多 Pod 副本并发下的严格保序独占消费
         */
        public QueueStep singleActiveConsumer() {
            this.currentQueue.arguments.put("x-single-active-consumer", true);
            return this;
        }

        /**
         * 设置消息在队列中的最大存活时间 (TTL)
         */
        public QueueStep ttl(Duration ttl) {
            Assert.notNull(ttl, "ttl 不能为空");
            Assert.isTrue(!ttl.isNegative(), "ttl 不能为负数");
            this.currentQueue.arguments.put("x-message-ttl", ttl.toMillis());
            return this;
        }

        /**
         * 设置队列最大消息条数限制
         */
        public QueueStep maxLength(long maxMessages) {
            Assert.isTrue(maxMessages > 0, "maxLength 必须大于 0");
            this.currentQueue.arguments.put("x-max-length", maxMessages);
            return this;
        }

        /**
         * 设置队列支持的最大消息优先级 (x-max-priority)
         * 开启后 Broker 会在内存为该队列维护多级优先级子队列，支持 1 到 255 级 (生产推荐 1-10 级)
         *
         * @param maxPriority 最大优先级 (1-255)
         * @return 队列级拓扑构建器
         */
        public QueueStep maxPriority(int maxPriority) {
            Assert.isTrue(maxPriority > 0 && maxPriority <= 255, "maxPriority 必须在 1 到 255 之间");
            this.currentQueue.arguments.put("x-max-priority", maxPriority);
            return this;
        }

        /**
         * 设置队列满载时的溢出流控策略 (x-overflow)
         *
         * @param overflowStrategy 溢出策略 (drop-head, reject-publish, reject-publish-dlx)
         * @return 队列级拓扑构建器
         */
        public QueueStep overflow(OverflowStrategy overflowStrategy) {
            Assert.notNull(overflowStrategy, "overflowStrategy 不能为空");
            this.currentQueue.arguments.put("x-overflow", overflowStrategy.getValue());
            return this;
        }

        /**
         * 设置现代 Quorum 仲裁队列的投递毒丸次数上限 (x-delivery-limit)
         * 当某条消息被连续 NACK/重回队列达到此阈值时，Broker 将自动阻断投递并流转至死信队列或丢弃，防御毒丸消息雪崩
         *
         * @param count 最大投递次数 (> 0)
         * @return 队列级拓扑构建器
         */
        public QueueStep deliveryLimit(int count) {
            Assert.isTrue(count > 0, "deliveryLimit 投递上限必须大于 0");
            this.currentQueue.arguments.put("x-delivery-limit", count);
            return this;
        }

        /**
         * 设置现代 Quorum 仲裁队列的死信流转策略枚举 (x-dead-letter-strategy)
         *
         * @param deadLetterStrategy 死信策略强类型枚举 (如 {@link DeadLetterStrategy#AT_LEAST_ONCE})
         * @return 队列级拓扑构建器
         */
        public QueueStep deadLetterStrategy(DeadLetterStrategy deadLetterStrategy) {
            Assert.notNull(deadLetterStrategy, "deadLetterStrategy 不能为 null");
            return deadLetterStrategy(deadLetterStrategy.getValue());
        }

        /**
         * 设置现代 Quorum 仲裁队列的死信流转策略 (x-dead-letter-strategy)
         * 例如设置为 "at-least-once"，保障死信投递过程中的严格至少一次可靠性
         *
         * @param strategy 死信策略 (如 at-least-once)
         * @return 队列级拓扑构建器
         */
        public QueueStep deadLetterStrategy(String strategy) {
            Assert.hasText(strategy, "deadLetterStrategy 死信策略不能为空");
            this.currentQueue.arguments.put("x-dead-letter-strategy", strategy.trim());
            return this;
        }

        /**
         * 设置队列持久化标志 (默认 true)
         */
        public QueueStep durable(boolean durable) {
            this.currentQueue.durable = durable;
            return this;
        }

        /**
         * 设置队列自动删除标志 (默认 false)
         */
        public QueueStep autoDelete(boolean autoDelete) {
            this.currentQueue.autoDelete = autoDelete;
            return this;
        }

        /**
         * 设置队列独占排他标志 (默认 false)
         */
        public QueueStep exclusive(boolean exclusive) {
            this.currentQueue.exclusive = exclusive;
            return this;
        }

        /**
         * 注入自定义队列高级参数
         */
        public QueueStep argument(String key, Object value) {
            Assert.hasText(key, "argument key 不能为空");
            this.currentQueue.arguments.put(key, value);
            return this;
        }

        /**
         * 指定绑定路由键
         */
        public QueueStep routingKey(String routingKey) {
            if (routingKey != null) {
                this.currentQueue.routingKeys.add(routingKey);
            }
            return this;
        }

        /**
         * 批量指定多个绑定路由键
         */
        public QueueStep routingKeys(String... routingKeys) {
            if (routingKeys != null) {
                for (String rk : routingKeys) {
                    if (rk != null) {
                        this.currentQueue.routingKeys.add(rk);
                    }
                }
            }
            return this;
        }

        /**
         * 声明标准死信拓扑 (DLX + DLQ + 死信绑定)，并严守死信终点不变量 (Terminal Invariant)
         *
         * @param dlxExchange   死信交换机名称
         * @param dlqQueue      死信队列名称
         * @param dlqRoutingKey 死信路由键
         */
        public QueueStep deadLetter(String dlxExchange, String dlqQueue, String dlqRoutingKey) {
            Assert.hasText(dlxExchange, "dlxExchange 死信交换机名称不能为空");
            Assert.hasText(dlqQueue, "dlqQueue 死信队列名称不能为空");
            Assert.hasText(dlqRoutingKey, "dlqRoutingKey 死信路由键不能为空");

            // 1. 终点不变量防卫：死信队列严禁重复挂载死信交换机
            String currentQueueName = this.currentQueue.queueName;
            if (currentQueueName.endsWith(".dlq") || currentQueueName.contains("dead-letter")) {
                throw new IllegalArgumentException("死信队列严禁再次声明死信交换机 (Terminal Invariant)，防止死信在队列间互咬死循环: " + currentQueueName);
            }
            Assert.isTrue(!currentQueueName.equals(dlqQueue), "死信队列名称不能与当前业务队列同名");
            Assert.isTrue(!exchange.getName().equals(dlxExchange), "死信交换机不能与业务交换机同名");
            Assert.isNull(this.currentQueue.dlxExchange, "当前队列已配置死信拓扑，严禁重复配置");

            // 2. 注入业务队列的死信投递属性
            this.currentQueue.arguments.put("x-dead-letter-exchange", dlxExchange);
            this.currentQueue.arguments.put("x-dead-letter-routing-key", dlqRoutingKey);

            this.currentQueue.dlxExchange = dlxExchange;
            this.currentQueue.dlqQueue = dlqQueue;
            this.currentQueue.dlqRoutingKey = dlqRoutingKey;

            return this;
        }

        /**
         * 声明原生 TTL+DLX 延迟暂存队列，过期后自动流转至当前交换机与当前队列
         *
         * @param ttlQueueName 延迟暂存队列名称
         * @param delay        延迟过期时间
         */
        public QueueStep ttlQueue(String ttlQueueName, Duration delay) {
            Assert.hasText(ttlQueueName, "ttlQueueName 不能为空");
            Assert.notNull(delay, "delay 延迟时间不能为空");
            Assert.isTrue(!delay.isNegative() && !delay.isZero(), "delay 必须大于 0");

            this.currentQueue.ttlQueueName = ttlQueueName;
            this.currentQueue.ttlDelay = delay;
            return this;
        }

        /**
         * 在同一交换机下继续声明下一个业务队列
         *
         * @param nextQueueName 下一个队列名称
         * @return 队列级拓扑构建器
         */
        public QueueStep andQueue(String nextQueueName) {
            Assert.hasText(nextQueueName, "nextQueueName 队列名称不能为空");
            this.currentQueue = new QueueDefinition(nextQueueName);
            this.queueDefinitions.add(this.currentQueue);
            return this;
        }

        /**
         * 在同一交换机下继续声明下一个动态匿名排他自动删除队列
         *
         * @return 队列级拓扑构建器
         */
        public QueueStep andAnonymousQueue() {
            this.currentQueue = new QueueDefinition(new AnonymousQueue());
            this.queueDefinitions.add(this.currentQueue);
            return this;
        }

        /**
         * 在同一交换机下继续声明下一个命名独占排他自动删除队列
         *
         * @param nextQueueName 下一个队列名称
         * @return 队列级拓扑构建器
         */
        public QueueStep andExclusiveAutoDeleteQueue(String nextQueueName) {
            Assert.hasText(nextQueueName, "nextQueueName 队列名称不能为空");
            this.currentQueue = new QueueDefinition(new Queue(nextQueueName, false, true, true));
            this.queueDefinitions.add(this.currentQueue);
            return this;
        }

        /**
         * 在同一交换机下继续声明下一个匿名独占排他自动删除队列
         *
         * @return 队列级拓扑构建器
         */
        public QueueStep andExclusiveAutoDeleteQueue() {
            return andAnonymousQueue();
        }

        /**
         * 结束链式声明并构建 Spring 原生 Declarables 集合，自动被 RabbitAdmin 识别注册
         *
         * @return Declarables 实例
         */
        public Declarables build() {
            List<Declarable> declarables = new ArrayList<>();
            // 1. 注册主交换机
            declarables.add(this.exchange);

            for (QueueDefinition def : this.queueDefinitions) {
                // 2. 注册主业务队列
                Queue mainQueue;
                if (def.rawQueue != null) {
                    mainQueue = def.rawQueue;
                    if (!def.arguments.isEmpty()) {
                        def.arguments.forEach(mainQueue::addArgument);
                    }
                } else {
                    mainQueue = new Queue(def.queueName, def.durable, def.exclusive, def.autoDelete, def.arguments);
                }
                declarables.add(mainQueue);

                // 3. 注册主队列与交换机的绑定关系 (未配置路由键时默认以队列名作为单一路由键)
                Set<String> effectiveRoutingKeys = def.routingKeys.isEmpty() ? Set.of(def.queueName) : def.routingKeys;
                if (this.exchange instanceof FanoutExchange fanout) {
                    declarables.add(BindingBuilder.bind(mainQueue).to(fanout));
                } else if (this.exchange instanceof TopicExchange topic) {
                    for (String rk : effectiveRoutingKeys) {
                        declarables.add(BindingBuilder.bind(mainQueue).to(topic).with(rk));
                    }
                } else if (this.exchange instanceof DirectExchange direct) {
                    for (String rk : effectiveRoutingKeys) {
                        declarables.add(BindingBuilder.bind(mainQueue).to(direct).with(rk));
                    }
                } else {
                    // 自定义或延迟交换机 (CustomExchange)
                    for (String rk : effectiveRoutingKeys) {
                        declarables.add(BindingBuilder.bind(mainQueue).to(this.exchange).with(rk).noargs());
                    }
                }

                // 4. 若配置了死信拓扑，注册 DLX 交换机、DLQ 队列与死信绑定 (DLQ 不带 DLX)
                if (def.dlxExchange != null) {
                    DirectExchange dlx = new DirectExchange(def.dlxExchange, true, false);
                    Queue dlq = new Queue(def.dlqQueue, true, false, false);
                    Binding dlqBinding = BindingBuilder.bind(dlq).to(dlx).with(def.dlqRoutingKey);

                    declarables.add(dlx);
                    declarables.add(dlq);
                    declarables.add(dlqBinding);
                }

                // 5. 若配置了原生 TTL+DLX 延迟暂存队列，注册暂存队列
                if (def.ttlQueueName != null) {
                    String targetRoutingKey = def.routingKeys.isEmpty() ? def.queueName : def.routingKeys.iterator().next();
                    Map<String, Object> ttlArgs = new HashMap<>();
                    ttlArgs.put("x-message-ttl", def.ttlDelay.toMillis());
                    ttlArgs.put("x-dead-letter-exchange", this.exchange.getName());
                    ttlArgs.put("x-dead-letter-routing-key", targetRoutingKey);

                    Queue ttlQueue = new Queue(def.ttlQueueName, true, false, false, ttlArgs);
                    declarables.add(ttlQueue);
                }
            }

            return new Declarables(declarables);
        }
    }

    /**
     * 单队列内部描述状态载体
     */
    private static class QueueDefinition {
        Queue rawQueue;
        final String queueName;
        boolean durable = true;
        boolean exclusive = false;
        boolean autoDelete = false;
        final Map<String, Object> arguments = new HashMap<>();
        final Set<String> routingKeys = new LinkedHashSet<>();

        String dlxExchange;
        String dlqQueue;
        String dlqRoutingKey;

        String ttlQueueName;
        Duration ttlDelay;

        QueueDefinition(String queueName) {
            this.queueName = queueName;
        }

        QueueDefinition(Queue queue) {
            this.rawQueue = Objects.requireNonNull(queue, "queue 不能为空");
            this.queueName = queue.getName();
            this.durable = queue.isDurable();
            this.exclusive = queue.isExclusive();
            this.autoDelete = queue.isAutoDelete();
            if (queue.getArguments() != null) {
                this.arguments.putAll(queue.getArguments());
            }
        }
    }
}
