package io.github.atengk.boot.rabbitmq.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RabbitTopologyBuilder 声明式拓扑构建器单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("RabbitTopologyBuilder 拓扑构建器测试")
class RabbitTopologyBuilderTest {

    @Test
    @DisplayName("Direct 交换机、Quorum 队列与单活消费者完整拓扑声明")
    void testDirectQuorumSingleActiveConsumerTopology() {
        Declarables declarables = RabbitTopologyBuilder.direct("order.direct")
                .queue("order.create.queue")
                .quorum()
                .singleActiveConsumer()
                .ttl(Duration.ofMinutes(30))
                .maxLength(50000)
                .routingKey("order.create")
                .build();

        List<Declarable> list = declarables.getDeclarables().stream().toList();
        assertThat(list).hasSize(3); // 1 Exchange + 1 Queue + 1 Binding

        DirectExchange exchange = (DirectExchange) list.get(0);
        assertThat(exchange.getName()).isEqualTo("order.direct");
        assertThat(exchange.isDurable()).isTrue();

        Queue queue = (Queue) list.get(1);
        assertThat(queue.getName()).isEqualTo("order.create.queue");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-queue-type", "quorum")
                .containsEntry("x-single-active-consumer", true)
                .containsEntry("x-message-ttl", 1800000L)
                .containsEntry("x-max-length", 50000L);

        Binding binding = (Binding) list.get(2);
        assertThat(binding.getExchange()).isEqualTo("order.direct");
        assertThat(binding.getDestination()).isEqualTo("order.create.queue");
        assertThat(binding.getRoutingKey()).isEqualTo("order.create");
        assertThat(binding.getDestinationType()).isEqualTo(Binding.DestinationType.QUEUE);
    }

    @Test
    @DisplayName("Topic 交换机多路由键与死信拓扑声明 (DLX + DLQ + 死信绑定)")
    void testTopicExchangeWithDeadLetterTopology() {
        Declarables declarables = RabbitTopologyBuilder.topic("log.topic")
                .queue("log.error.queue")
                .routingKeys("log.error.#", "log.warn.#")
                .deadLetter("log.dlx", "log.error.dlq", "log.error.dead")
                .build();

        List<Declarable> list = declarables.getDeclarables().stream().toList();
        // 1 TopicExchange + 1 Queue + 2 Bindings + 1 DLX + 1 DLQ + 1 DLQ Binding = 7
        assertThat(list).hasSize(7);

        TopicExchange exchange = (TopicExchange) list.get(0);
        assertThat(exchange.getName()).isEqualTo("log.topic");

        Queue queue = (Queue) list.get(1);
        assertThat(queue.getName()).isEqualTo("log.error.queue");
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", "log.dlx")
                .containsEntry("x-dead-letter-routing-key", "log.error.dead");

        // 验证主队列的 2 个 Topic 绑定
        List<Binding> topicBindings = list.stream()
                .filter(d -> d instanceof Binding b && b.getExchange().equals("log.topic"))
                .map(d -> (Binding) d)
                .toList();
        assertThat(topicBindings).hasSize(2);
        assertThat(topicBindings).extracting(Binding::getRoutingKey)
                .containsExactlyInAnyOrder("log.error.#", "log.warn.#");

        // 验证 DLX 与 DLQ
        DirectExchange dlx = list.stream()
                .filter(d -> d instanceof DirectExchange de && de.getName().equals("log.dlx"))
                .map(d -> (DirectExchange) d)
                .findFirst().orElseThrow();
        assertThat(dlx.isDurable()).isTrue();

        Queue dlq = list.stream()
                .filter(d -> d instanceof Queue q && q.getName().equals("log.error.dlq"))
                .map(d -> (Queue) d)
                .findFirst().orElseThrow();
        assertThat(dlq.isDurable()).isTrue();
        // DLQ 绝不能含有 x-dead-letter-exchange
        assertThat(dlq.getArguments()).doesNotContainKey("x-dead-letter-exchange");

        Binding dlqBinding = list.stream()
                .filter(d -> d instanceof Binding b && b.getExchange().equals("log.dlx"))
                .map(d -> (Binding) d)
                .findFirst().orElseThrow();
        assertThat(dlqBinding.getDestination()).isEqualTo("log.error.dlq");
        assertThat(dlqBinding.getRoutingKey()).isEqualTo("log.error.dead");
    }

    @Test
    @DisplayName("Fanout 广播交换机绑定多队列拓扑声明")
    void testFanoutExchangeMultiQueueTopology() {
        Declarables declarables = RabbitTopologyBuilder.fanout("broadcast.fanout")
                .queue("sub1.queue")
                .andQueue("sub2.queue")
                .build();

        List<Declarable> list = declarables.getDeclarables().stream().toList();
        // 1 FanoutExchange + 2 Queues + 2 Bindings = 5
        assertThat(list).hasSize(5);

        FanoutExchange exchange = (FanoutExchange) list.get(0);
        assertThat(exchange.getName()).isEqualTo("broadcast.fanout");

        List<Queue> queues = list.stream().filter(d -> d instanceof Queue).map(d -> (Queue) d).toList();
        assertThat(queues).extracting(Queue::getName).containsExactly("sub1.queue", "sub2.queue");

        List<Binding> bindings = list.stream().filter(d -> d instanceof Binding).map(d -> (Binding) d).toList();
        assertThat(bindings).hasSize(2);
        assertThat(bindings).allMatch(b -> b.getExchange().equals("broadcast.fanout") && b.getRoutingKey().isEmpty());
    }

    @Test
    @DisplayName("延迟插件交换机 (x-delayed-message) 拓扑声明")
    void testDelayedPluginExchangeTopology() {
        Declarables declarables = RabbitTopologyBuilder.delayed("delay.exchange", "topic")
                .queue("delay.order.queue")
                .routingKey("order.timeout")
                .build();

        List<Declarable> list = declarables.getDeclarables().stream().toList();
        assertThat(list).hasSize(3);

        CustomExchange exchange = (CustomExchange) list.get(0);
        assertThat(exchange.getName()).isEqualTo("delay.exchange");
        assertThat(exchange.getType()).isEqualTo("x-delayed-message");
        assertThat(exchange.getArguments()).containsEntry("x-delayed-type", "topic");

        Queue queue = (Queue) list.get(1);
        assertThat(queue.getName()).isEqualTo("delay.order.queue");

        Binding binding = (Binding) list.get(2);
        assertThat(binding.getExchange()).isEqualTo("delay.exchange");
        assertThat(binding.getRoutingKey()).isEqualTo("order.timeout");
    }

    @Test
    @DisplayName("纯原生零插件 TTL+DLX 双模延迟拓扑声明")
    void testNativeTtlDlxDelayQueueTopology() {
        // 1. 链式附挂 ttlQueue
        Declarables declarables = RabbitTopologyBuilder.direct("order.direct")
                .queue("order.payment.queue")
                .routingKey("order.payment")
                .ttlQueue("order.payment.delay.30m", Duration.ofMinutes(30))
                .build();

        List<Declarable> list = declarables.getDeclarables().stream().toList();
        // 1 DirectExchange + 1 Main Queue + 1 Main Binding + 1 TTL Queue = 4
        assertThat(list).hasSize(4);

        Queue ttlQueue = list.stream()
                .filter(d -> d instanceof Queue q && q.getName().equals("order.payment.delay.30m"))
                .map(d -> (Queue) d)
                .findFirst().orElseThrow();

        assertThat(ttlQueue.getArguments())
                .containsEntry("x-message-ttl", 1800000L)
                .containsEntry("x-dead-letter-exchange", "order.direct")
                .containsEntry("x-dead-letter-routing-key", "order.payment");

        // 2. 独立静态方法声明 ttlDelayQueue
        Declarables standalone = RabbitTopologyBuilder.ttlDelayQueue(
                "stock.unlock.delay.10m",
                Duration.ofMinutes(10),
                "stock.direct",
                "stock.unlock"
        );
        assertThat(standalone.getDeclarables()).hasSize(1);
        Queue standaloneQueue = (Queue) standalone.getDeclarables().iterator().next();
        assertThat(standaloneQueue.getName()).isEqualTo("stock.unlock.delay.10m");
        assertThat(standaloneQueue.getArguments())
                .containsEntry("x-message-ttl", 600000L)
                .containsEntry("x-dead-letter-exchange", "stock.direct")
                .containsEntry("x-dead-letter-routing-key", "stock.unlock");
    }

    @Test
    @DisplayName("死信终点不变量强校验：严禁在死信队列上重复配置 DLX 或队列循环")
    void testDeadLetterTerminalInvariantGuards() {
        // 1. 队列名以 .dlq 结尾时禁止声明 deadLetter
        assertThatThrownBy(() ->
                RabbitTopologyBuilder.direct("test.direct")
                        .queue("order.cancel.dlq")
                        .deadLetter("dlx.exchange", "other.dlq", "other.key")
                        .build()
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Terminal Invariant");

        // 2. 队列名包含 dead-letter 时禁止声明 deadLetter
        assertThatThrownBy(() ->
                RabbitTopologyBuilder.direct("test.direct")
                        .queue("dead-letter-queue")
                        .deadLetter("dlx.exchange", "other.dlq", "other.key")
                        .build()
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Terminal Invariant");

        // 3. 死信队列不能与当前业务队列同名
        assertThatThrownBy(() ->
                RabbitTopologyBuilder.direct("test.direct")
                        .queue("same.queue")
                        .deadLetter("dlx.exchange", "same.queue", "same.key")
                        .build()
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能与当前业务队列同名");

        // 4. 死信交换机不能与业务交换机同名
        assertThatThrownBy(() ->
                RabbitTopologyBuilder.direct("same.exchange")
                        .queue("biz.queue")
                        .deadLetter("same.exchange", "biz.dlq", "biz.key")
                        .build()
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能与业务交换机同名");

        // 5. 单队列禁止重复声明 deadLetter
        assertThatThrownBy(() ->
                RabbitTopologyBuilder.direct("test.direct")
                        .queue("biz.queue")
                        .deadLetter("dlx.1", "dlq.1", "key.1")
                        .deadLetter("dlx.2", "dlq.2", "key.2")
                        .build()
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("严禁重复配置");
    }

    @Test
    @DisplayName("入参非空与非法参数防御校验")
    void testValidationGuards() {
        assertThatThrownBy(() -> RabbitTopologyBuilder.direct(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RabbitTopologyBuilder.topic(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RabbitTopologyBuilder.fanout(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RabbitTopologyBuilder.delayed(""))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> RabbitTopologyBuilder.direct("test").queue((String) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RabbitTopologyBuilder.direct("test").queue((Queue) null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> RabbitTopologyBuilder.direct("test").queue("q").ttl(Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> RabbitTopologyBuilder.direct("test").queue("q").maxLength(-10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("现代 Quorum 拓扑高级属性声明 (maxPriority, overflow, deliveryLimit, deadLetterStrategy)")
    void testQuorumQueueWithModernTopologyArguments() {
        Declarables declarables = RabbitTopologyBuilder.topic("order.modern.topic")
                .queue("order.payment.quorum")
                .quorum()
                .maxPriority(10)
                .overflow(OverflowStrategy.REJECT_PUBLISH_DLX)
                .deliveryLimit(5)
                .deadLetterStrategy("at-least-once")
                .deadLetter("order.dlx", "order.payment.dlq", "order.payment.dead")
                .routingKey("order.payment.*")
                .build();

        List<Declarable> list = declarables.getDeclarables().stream().toList();
        Queue queue = list.stream()
                .filter(d -> d instanceof Queue q && q.getName().equals("order.payment.quorum"))
                .map(d -> (Queue) d)
                .findFirst()
                .orElseThrow();

        assertThat(queue.getArguments())
                .containsEntry("x-queue-type", "quorum")
                .containsEntry("x-max-priority", 10)
                .containsEntry("x-overflow", "reject-publish-dlx")
                .containsEntry("x-delivery-limit", 5)
                .containsEntry("x-dead-letter-strategy", "at-least-once")
                .containsEntry("x-dead-letter-exchange", "order.dlx")
                .containsEntry("x-dead-letter-routing-key", "order.payment.dead");
    }

    @Test
    @DisplayName("Exchange 备用交换机 (alternate-exchange) 路由拓扑声明")
    void testAlternateExchangeTopology() {
        Declarables declarables = RabbitTopologyBuilder.direct("main.direct")
                .alternateExchange("unrouted.fanout")
                .queue("main.worker.queue")
                .routingKey("main.work")
                .build();

        List<Declarable> list = declarables.getDeclarables().stream().toList();
        DirectExchange exchange = list.stream()
                .filter(d -> d instanceof DirectExchange de && de.getName().equals("main.direct"))
                .map(d -> (DirectExchange) d)
                .findFirst()
                .orElseThrow();

        assertThat(exchange.getArguments()).containsEntry("alternate-exchange", "unrouted.fanout");
    }

    @Test
    @DisplayName("Fanout 交换机匿名排他自动删除队列与临时独占队列拓扑声明")
    void testAnonymousQueueAndExclusiveAutoDeleteQueue() {
        Declarables declarables = RabbitTopologyBuilder.fanout("broadcast.fanout")
                .anonymousQueue()
                .andExclusiveAutoDeleteQueue("worker.node1.queue")
                .build();

        List<Declarable> list = declarables.getDeclarables().stream().toList();
        // 1 FanoutExchange + 2 Queues + 2 Bindings = 5
        assertThat(list).hasSize(5);

        List<Queue> queues = list.stream()
                .filter(d -> d instanceof Queue)
                .map(d -> (Queue) d)
                .toList();
        assertThat(queues).hasSize(2);

        Queue anonQueue = queues.get(0);
        assertThat(anonQueue.getName()).isNotBlank();
        assertThat(anonQueue.isDurable()).isFalse();
        assertThat(anonQueue.isExclusive()).isTrue();
        assertThat(anonQueue.isAutoDelete()).isTrue();

        Queue explicitQueue = queues.get(1);
        assertThat(explicitQueue.getName()).isEqualTo("worker.node1.queue");
        assertThat(explicitQueue.isDurable()).isFalse();
        assertThat(explicitQueue.isExclusive()).isTrue();
        assertThat(explicitQueue.isAutoDelete()).isTrue();

        // 验证两个队列均正确绑定至广播交换机
        List<Binding> bindings = list.stream()
                .filter(d -> d instanceof Binding)
                .map(d -> (Binding) d)
                .toList();
        assertThat(bindings).hasSize(2);
        assertThat(bindings).extracting(Binding::getDestination)
                .containsExactlyInAnyOrder(anonQueue.getName(), "worker.node1.queue");
    }

    @Test
    @DisplayName("现代 Quorum 与备用交换机高级参数非法校验防御")
    void testModernTopologyValidationGuards() {
        // maxPriority 边界校验
        assertThatThrownBy(() -> RabbitTopologyBuilder.direct("test").queue("q").maxPriority(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RabbitTopologyBuilder.direct("test").queue("q").maxPriority(256))
                .isInstanceOf(IllegalArgumentException.class);

        // overflow 非空校验
        assertThatThrownBy(() -> RabbitTopologyBuilder.direct("test").queue("q").overflow(null))
                .isInstanceOf(IllegalArgumentException.class);

        // deliveryLimit 必须大于 0
        assertThatThrownBy(() -> RabbitTopologyBuilder.direct("test").queue("q").deliveryLimit(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RabbitTopologyBuilder.direct("test").queue("q").deliveryLimit(-5))
                .isInstanceOf(IllegalArgumentException.class);

        // deadLetterStrategy 非空校验
        assertThatThrownBy(() -> RabbitTopologyBuilder.direct("test").queue("q").deadLetterStrategy(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RabbitTopologyBuilder.direct("test").queue("q").deadLetterStrategy((DeadLetterStrategy) null))
                .isInstanceOf(IllegalArgumentException.class);

        // 验证枚举绑定参数正常生效
        Declarables enumDecl = RabbitTopologyBuilder.direct("test")
                .queue("q")
                .deadLetterStrategy(DeadLetterStrategy.AT_LEAST_ONCE)
                .build();
        Queue q = (Queue) enumDecl.getDeclarables().stream().filter(d -> d instanceof Queue).findFirst().orElseThrow();
        assertThat(q.getArguments()).containsEntry("x-dead-letter-strategy", "at-least-once");

        // alternateExchange 非空与同名校验
        assertThatThrownBy(() -> RabbitTopologyBuilder.direct("test").alternateExchange(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RabbitTopologyBuilder.direct("test.exchange").alternateExchange("test.exchange"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("支持仅声明交换机而不挂载静态队列 (build)")
    void testExchangeOnlyBuild() {
        Declarables fanoutDecl = RabbitTopologyBuilder.fanout("sample.fanout.standalone").build();
        assertThat(fanoutDecl.getDeclarables()).hasSize(1);
        assertThat(fanoutDecl.getDeclarables().iterator().next()).isInstanceOf(FanoutExchange.class);
        FanoutExchange fanoutExchange = (FanoutExchange) fanoutDecl.getDeclarables().iterator().next();
        assertThat(fanoutExchange.getName()).isEqualTo("sample.fanout.standalone");
    }
}
