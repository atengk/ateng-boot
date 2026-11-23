package io.github.atengk.boot.redis.delay;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.redis.delay.model.DeadLetterMessage;
import io.github.atengk.boot.redis.properties.RedisProperties;
import io.github.atengk.boot.redis.serializer.RedisSerializerFactory;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编程式延时队列 RedisDelayedQueue 单元测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisDelayedQueue 单元测试")
class RedisDelayedQueueTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RBlockingQueue<Object> blockingQueue;

    @Mock
    private RDelayedQueue<Object> delayedQueue;

    private ObjectMapper objectMapper;
    private RedisDelayedQueue redisDelayedQueue;

    static class OrderMessage {
        private String orderNo;
        private Double amount;

        public OrderMessage() {
        }

        public OrderMessage(String orderNo, Double amount) {
            this.orderNo = orderNo;
            this.amount = amount;
        }

        public String getOrderNo() {
            return orderNo;
        }

        public void setOrderNo(String orderNo) {
            this.orderNo = orderNo;
        }

        public Double getAmount() {
            return amount;
        }

        public void setAmount(Double amount) {
            this.amount = amount;
        }
    }

    @BeforeEach
    void setUp() {
        objectMapper = RedisSerializerFactory.createSafeObjectMapper();
        redisDelayedQueue = new RedisDelayedQueue(redissonClient, objectMapper);
        RedisDelayedQueueUtil.init(redisDelayedQueue);
    }

    @Test
    @DisplayName("测试 offer 入队操作（基于数值延时与 Duration）")
    void testOffer() {
        when(redissonClient.getBlockingQueue("order-cancel-queue")).thenReturn(blockingQueue);
        when(redissonClient.getDelayedQueue(blockingQueue)).thenReturn(delayedQueue);

        redisDelayedQueue.offer("order-cancel-queue", "ORD-1001", 30L, TimeUnit.MINUTES);
        verify(delayedQueue).offer("ORD-1001", 30L, TimeUnit.MINUTES);

        redisDelayedQueue.offer("order-cancel-queue", "ORD-1002", Duration.ofSeconds(15));
        verify(delayedQueue).offer("ORD-1002", 15000L, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("测试 offer 参数校验防御")
    void testOfferValidation() {
        assertThatThrownBy(() -> redisDelayedQueue.offer("", "msg", 10, TimeUnit.SECONDS))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> redisDelayedQueue.offer("queue", null, 10, TimeUnit.SECONDS))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> redisDelayedQueue.offer("queue", "msg", -1, TimeUnit.SECONDS))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> redisDelayedQueue.offer("queue", "msg", 10, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("测试非阻塞 poll 及类型自动转换")
    void testPoll() {
        when(redissonClient.getBlockingQueue("order-queue")).thenReturn(blockingQueue);
        when(blockingQueue.poll()).thenReturn("MSG-001");

        String msg = redisDelayedQueue.poll("order-queue");
        assertThat(msg).isEqualTo("MSG-001");

        Map<String, Object> mapPayload = new HashMap<>();
        mapPayload.put("orderNo", "ORD-8888");
        mapPayload.put("amount", 99.9);
        when(blockingQueue.poll()).thenReturn(mapPayload);

        OrderMessage order = redisDelayedQueue.poll("order-queue", OrderMessage.class);
        assertThat(order).isNotNull();
        assertThat(order.getOrderNo()).isEqualTo("ORD-8888");
        assertThat(order.getAmount()).isEqualTo(99.9);
    }

    @Test
    @DisplayName("测试超时阻塞 poll 及类型自动转换")
    void testPollWithTimeout() throws InterruptedException {
        when(redissonClient.getBlockingQueue("order-queue")).thenReturn(blockingQueue);
        when(blockingQueue.poll(5L, TimeUnit.SECONDS)).thenReturn("READY-MSG");

        String res = redisDelayedQueue.poll("order-queue", 5L, TimeUnit.SECONDS, String.class);
        assertThat(res).isEqualTo("READY-MSG");
        verify(blockingQueue).poll(5L, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("测试阻塞 take 及 peek 查看")
    void testTakeAndPeek() throws InterruptedException {
        when(redissonClient.getBlockingQueue("order-queue")).thenReturn(blockingQueue);
        when(blockingQueue.take()).thenReturn("TAKEN-MSG");
        when(blockingQueue.peek()).thenReturn("PEEK-MSG");

        String taken = redisDelayedQueue.take("order-queue", String.class);
        assertThat(taken).isEqualTo("TAKEN-MSG");

        String peeked = redisDelayedQueue.peek("order-queue", String.class);
        assertThat(peeked).isEqualTo("PEEK-MSG");
    }

    @Test
    @DisplayName("测试队列长度、清空与移除操作")
    void testSizeClearRemove() {
        when(redissonClient.getBlockingQueue("test-queue")).thenReturn(blockingQueue);
        when(redissonClient.getDelayedQueue(blockingQueue)).thenReturn(delayedQueue);

        when(blockingQueue.size()).thenReturn(5);
        when(delayedQueue.size()).thenReturn(10);

        assertThat(redisDelayedQueue.size("test-queue")).isEqualTo(5);
        assertThat(redisDelayedQueue.delayedSize("test-queue")).isEqualTo(10);

        redisDelayedQueue.clear("test-queue");
        verify(blockingQueue).clear();
        verify(delayedQueue).clear();

        when(delayedQueue.remove("msg-1")).thenReturn(true);
        boolean removed = redisDelayedQueue.remove("test-queue", "msg-1");
        assertThat(removed).isTrue();
    }

    @Test
    @DisplayName("测试销毁队列与全部销毁")
    void testDestroy() {
        when(redissonClient.getBlockingQueue("queue-1")).thenReturn(blockingQueue);
        when(redissonClient.getDelayedQueue(blockingQueue)).thenReturn(delayedQueue);

        // 缓存延时队列
        redisDelayedQueue.getDelayedQueue("queue-1");
        redisDelayedQueue.destroy("queue-1");
        verify(delayedQueue, org.mockito.Mockito.times(1)).destroy();

        redisDelayedQueue.getDelayedQueue("queue-1");
        redisDelayedQueue.destroyAll();
        verify(delayedQueue, org.mockito.Mockito.times(2)).destroy();
    }

    @Test
    @DisplayName("测试死信队列投递、拉取与长度清空操作")
    void testDeadLetterQueueOperations() {
        RBlockingQueue<Object> dlqBlockingQueue = org.mockito.Mockito.mock(RBlockingQueue.class);
        when(redissonClient.getBlockingQueue("order-queue:dlq")).thenReturn(dlqBlockingQueue);

        io.github.atengk.boot.redis.delay.model.DeadLetterMessage<OrderMessage> msg =
                new io.github.atengk.boot.redis.delay.model.DeadLetterMessage<>(
                        "order-queue",
                        new OrderMessage("ORD-999", 199.0),
                        3,
                        "扣款失败",
                        "java.lang.IllegalStateException",
                        java.time.LocalDateTime.now()
                );

        // 1. 发送死信
        redisDelayedQueue.sendToDeadLetterQueue("order-queue", msg);
        verify(dlqBlockingQueue).offer(msg);

        // 1.1 sendToDeadLetter 别名
        redisDelayedQueue.sendToDeadLetter("order-queue", msg);
        verify(dlqBlockingQueue, org.mockito.Mockito.times(2)).offer(msg);

        // 2. 死信队列长度
        when(dlqBlockingQueue.size()).thenReturn(1);
        assertThat(redisDelayedQueue.deadLetterSize("order-queue")).isEqualTo(1);
        assertThat(redisDelayedQueue.getDeadLetterSize("order-queue")).isEqualTo(1);

        // 3. 查看死信 peek
        when(dlqBlockingQueue.peek()).thenReturn(msg);
        io.github.atengk.boot.redis.delay.model.DeadLetterMessage<OrderMessage> peeked =
                redisDelayedQueue.peekDeadLetter("order-queue", OrderMessage.class);
        assertThat(peeked).isNotNull();
        assertThat(peeked.payload().getOrderNo()).isEqualTo("ORD-999");

        // 4. 拉取死信 poll
        when(dlqBlockingQueue.poll()).thenReturn(msg);
        io.github.atengk.boot.redis.delay.model.DeadLetterMessage<OrderMessage> polled =
                redisDelayedQueue.pollDeadLetter("order-queue", OrderMessage.class);
        assertThat(polled).isNotNull();
        assertThat(polled.originalQueue()).isEqualTo("order-queue");
        assertThat(polled.retryTimes()).isEqualTo(3);
        assertThat(polled.errorMessage()).isEqualTo("扣款失败");
        assertThat(polled.payload().getOrderNo()).isEqualTo("ORD-999");

        // 5. 清空死信
        redisDelayedQueue.clearDeadLetterQueue("order-queue");
        verify(dlqBlockingQueue).clear();

        redisDelayedQueue.clearDeadLetter("order-queue");
        verify(dlqBlockingQueue, org.mockito.Mockito.times(2)).clear();
    }

    @Test
    @DisplayName("测试全局 keyPrefix 与死信队列名解析")
    void testQueueNameResolutionWithKeyPrefix() {
        io.github.atengk.boot.redis.properties.RedisProperties properties = new io.github.atengk.boot.redis.properties.RedisProperties();
        properties.setKeyPrefix("myapp:");
        properties.getDelayQueue().setDeadLetterSuffix(".dead");

        RedisDelayedQueue prefixedQueue = new RedisDelayedQueue(redissonClient, objectMapper, properties);

        assertThat(prefixedQueue.resolveQueueName("pay_task")).isEqualTo("myapp:pay_task");
        // 已有前缀不重复追加
        assertThat(prefixedQueue.resolveQueueName("myapp:pay_task")).isEqualTo("myapp:pay_task");

        assertThat(prefixedQueue.resolveDeadLetterQueueName("pay_task")).isEqualTo("myapp:pay_task.dead");
    }

    @Test
    @DisplayName("测试批量非破坏性检视死信 (peekDeadLetters)")
    @SuppressWarnings("unchecked")
    void testPeekDeadLettersBatch() {
        RBlockingQueue<Object> dlqBlockingQueue = org.mockito.Mockito.mock(RBlockingQueue.class);
        when(redissonClient.getBlockingQueue("peek-queue:dlq")).thenReturn(dlqBlockingQueue);

        DeadLetterMessage<OrderMessage> msg1 = new DeadLetterMessage<>(
                "peek-queue", new OrderMessage("ORD-1", 10.0), 3, "fail1", "Ex", LocalDateTime.now(), 0
        );
        DeadLetterMessage<OrderMessage> msg2 = new DeadLetterMessage<>(
                "peek-queue", new OrderMessage("ORD-2", 20.0), 3, "fail2", "Ex", LocalDateTime.now(), 1
        );

        when(dlqBlockingQueue.iterator()).thenReturn(List.<Object>of(msg1, msg2).iterator());

        List<DeadLetterMessage<OrderMessage>> result = redisDelayedQueue.peekDeadLetters("peek-queue", 5, OrderMessage.class);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).payload().getOrderNo()).isEqualTo("ORD-1");
        assertThat(result.get(0).replayCount()).isEqualTo(0);
        assertThat(result.get(1).payload().getOrderNo()).isEqualTo("ORD-2");
        assertThat(result.get(1).replayCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("测试受控重放死信及 maxReplayAttempts 防雪崩上限阻断")
    @SuppressWarnings("unchecked")
    void testReplayDeadLetterAndAvalancheGuard() {
        RedisProperties props = new RedisProperties();
        props.getDelayQueue().setMaxReplayAttempts(2);
        RedisDelayedQueue queueWithProps = new RedisDelayedQueue(redissonClient, objectMapper, props);

        RBlockingQueue<Object> dlqBlockingQueue = org.mockito.Mockito.mock(RBlockingQueue.class);
        when(redissonClient.getBlockingQueue("replay-queue:dlq")).thenReturn(dlqBlockingQueue);

        when(redissonClient.getBlockingQueue("replay-queue")).thenReturn(blockingQueue);
        when(redissonClient.getDelayedQueue(blockingQueue)).thenReturn(delayedQueue);

        // 场景 1：首次重放 (replayCount = 0 < 2) -> 允许重放，入队新死信 (replayCount = 1)
        DeadLetterMessage<String> firstDead = new DeadLetterMessage<>(
                "replay-queue", "task-payload", 3, "err", "Ex", LocalDateTime.now(), 0
        );
        when(dlqBlockingQueue.poll()).thenReturn(firstDead);

        boolean replayed = queueWithProps.replayDeadLetter("replay-queue", Duration.ofSeconds(5));
        assertThat(replayed).isTrue();
        verify(delayedQueue).offer(org.mockito.ArgumentMatchers.argThat((DeadLetterMessage<?> d) -> d.replayCount() == 1), eq(5000L), eq(TimeUnit.MILLISECONDS));

        // 场景 2：达到重放上限 (replayCount = 2 >= 2) -> 拒绝重放
        DeadLetterMessage<String> exhaustedDead = new DeadLetterMessage<>(
                "replay-queue", "task-payload", 3, "err", "Ex", LocalDateTime.now(), 2
        );
        when(dlqBlockingQueue.poll()).thenReturn(exhaustedDead);

        boolean blocked = queueWithProps.replayDeadLetter("replay-queue", Duration.ZERO);
        assertThat(blocked).isFalse();
        // 验证超限死信被补偿塞回死信队列队尾，未静默丢失
        verify(dlqBlockingQueue).offer(exhaustedDead);
    }

    @Test
    @DisplayName("测试批量重放死信时跳过超限死信且持续重放后续有效死信")
    @SuppressWarnings("unchecked")
    void testBatchReplayDeadLettersWithExhaustedSkipping() {
        RedisProperties props = new RedisProperties();
        props.getDelayQueue().setMaxReplayAttempts(2);
        RedisDelayedQueue queueWithProps = new RedisDelayedQueue(redissonClient, objectMapper, props);

        RBlockingQueue<Object> dlqBlockingQueue = org.mockito.Mockito.mock(RBlockingQueue.class);
        when(redissonClient.getBlockingQueue("batch-queue:dlq")).thenReturn(dlqBlockingQueue);
        when(redissonClient.getBlockingQueue("batch-queue")).thenReturn(blockingQueue);
        when(redissonClient.getDelayedQueue(blockingQueue)).thenReturn(delayedQueue);

        DeadLetterMessage<String> dead1 = new DeadLetterMessage<>(
                "batch-queue", "poison-pill", 3, "err", "Ex", LocalDateTime.now(), 2
        );
        DeadLetterMessage<String> dead2 = new DeadLetterMessage<>(
                "batch-queue", "good-payload", 3, "err", "Ex", LocalDateTime.now(), 0
        );

        when(dlqBlockingQueue.size()).thenReturn(2);
        when(dlqBlockingQueue.poll()).thenReturn(dead1, dead2);

        int replayedCount = queueWithProps.replayDeadLetters("batch-queue", 2, Duration.ZERO);
        assertThat(replayedCount).isEqualTo(1);
        verify(dlqBlockingQueue).offer(dead1);
        verify(delayedQueue).offer(org.mockito.ArgumentMatchers.argThat((DeadLetterMessage<?> d) -> "good-payload".equals(d.payload())), eq(0L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("测试 RedisDelayedQueueUtil 静态门面委托")
    @SuppressWarnings("unchecked")
    void testRedisDelayedQueueUtilStaticDelegation() {
        when(redissonClient.getBlockingQueue("static-queue:dlq")).thenReturn(blockingQueue);
        when(blockingQueue.size()).thenReturn(5);

        assertThat(RedisDelayedQueueUtil.deadLetterSize("static-queue")).isEqualTo(5);
        assertThat(RedisDelayedQueueUtil.getDeadLetterSize("static-queue")).isEqualTo(5);
    }

    @Test
    @DisplayName("测试 resolveQueueName 规范化无冒号前缀并幂等去重")
    void testResolveQueueNameIdempotentNormalization() {
        RedisProperties props = new RedisProperties();
        props.setKeyPrefix("prod");
        RedisDelayedQueue queueWithNoColon = new RedisDelayedQueue(redissonClient, objectMapper, props);

        assertThat(queueWithNoColon.resolveQueueName("pay_task")).isEqualTo("prod:pay_task");
        assertThat(queueWithNoColon.resolveQueueName("prod:pay_task")).isEqualTo("prod:pay_task");

        props.setKeyPrefix("sample:");
        assertThat(queueWithNoColon.resolveQueueName(":pay_task")).isEqualTo("sample:pay_task");
    }
}

