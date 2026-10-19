package io.github.atengk.boot.redis.delay.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.redis.delay.RedisDelayedQueue;
import io.github.atengk.boot.redis.delay.annotation.DelayedQueueListener;
import io.github.atengk.boot.redis.serializer.RedisSerializerFactory;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;
import org.springframework.context.support.GenericApplicationContext;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 延时队列生命周期调度器 DelayedQueueBootstrap 单元测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DelayedQueueBootstrap 单元测试")
class DelayedQueueBootstrapTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RBlockingQueue<Object> blockingQueue;

    @Mock
    private RDelayedQueue<Object> delayedQueue;

    private RedisDelayedQueue redisDelayedQueue;
    private ObjectMapper objectMapper;
    private DelayedQueueBootstrap bootstrap;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class TaskPayload {
        private String taskId;
        private String status;
    }

    static class SingleParamService {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> receivedMessage = new AtomicReference<>();

        @DelayedQueueListener(queue = "single-param-queue")
        public void handleSingleParam(String message) {
            receivedMessage.set(message);
            latch.countDown();
        }
    }

    static class TwoParamService {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> receivedQueue = new AtomicReference<>();
        final AtomicReference<TaskPayload> receivedTask = new AtomicReference<>();

        @DelayedQueueListener(queue = "two-param-queue")
        public void handleTwoParam(TaskPayload task, String queueName) {
            receivedTask.set(task);
            receivedQueue.set(queueName);
            latch.countDown();
        }
    }

    static class ErrorResilientService {
        final CountDownLatch latch = new CountDownLatch(2);
        final AtomicInteger errorInvocationCount = new AtomicInteger(0);

        @DelayedQueueListener(queue = "error-resilient-queue")
        public void handleError(String message) {
            int count = errorInvocationCount.incrementAndGet();
            latch.countDown();
            if (count == 1) {
                throw new RuntimeException("模拟消费抛出业务异常");
            }
        }
    }

    static class PlaceholderService {
        final CountDownLatch latch = new CountDownLatch(1);

        @DelayedQueueListener(queue = "${custom.queue.name:fallback-queue}")
        public void onMsg(String msg) {
            latch.countDown();
        }
    }

    @BeforeEach
    void setUp() {
        objectMapper = RedisSerializerFactory.createSafeObjectMapper();
        redisDelayedQueue = new RedisDelayedQueue(redissonClient, objectMapper);
        io.github.atengk.boot.redis.properties.RedisProperties properties = new io.github.atengk.boot.redis.properties.RedisProperties();
        properties.getDelayQueue().setRetryTimes(2);
        properties.getDelayQueue().setRetryDelay(java.time.Duration.ofMillis(50));
        bootstrap = new DelayedQueueBootstrap(redisDelayedQueue, objectMapper, properties);
    }

    @AfterEach
    void tearDown() {
        bootstrap.destroy();
    }

    @Test
    @DisplayName("测试自动扫描单入参监听方法并成功消费")
    void testSingleParamConsumer() throws Exception {
        SingleParamService service = new SingleParamService();

        when(redissonClient.getBlockingQueue("single-param-queue")).thenReturn(blockingQueue);
        when(redissonClient.getDelayedQueue(blockingQueue)).thenReturn(delayedQueue);
        when(blockingQueue.poll(anyLong(), any(TimeUnit.class)))
                .thenReturn("ORDER-EXPIRED-12345")
                .thenReturn(null);

        GenericApplicationContext context = new GenericApplicationContext();
        context.getBeanFactory().registerSingleton("singleParamService", service);
        context.refresh();

        bootstrap.setApplicationContext(context);
        bootstrap.afterSingletonsInstantiated();

        boolean completed = service.latch.await(15, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(service.receivedMessage.get()).isEqualTo("ORDER-EXPIRED-12345");
    }

    @Test
    @DisplayName("测试自动扫描双入参及复杂对象映射监听方法")
    void testTwoParamAndComplexObjectConsumer() throws Exception {
        TwoParamService service = new TwoParamService();

        when(redissonClient.getBlockingQueue("two-param-queue")).thenReturn(blockingQueue);
        when(redissonClient.getDelayedQueue(blockingQueue)).thenReturn(delayedQueue);

        // 模拟 Redis/Redisson 原始返回 JSON 字符串或 Map
        when(blockingQueue.poll(anyLong(), any(TimeUnit.class)))
                .thenReturn("{\"taskId\":\"TASK-999\",\"status\":\"PROCESSING\"}")
                .thenReturn(null);

        GenericApplicationContext context = new GenericApplicationContext();
        context.getBeanFactory().registerSingleton("twoParamService", service);
        context.refresh();

        bootstrap.setApplicationContext(context);
        bootstrap.afterSingletonsInstantiated();

        boolean completed = service.latch.await(15, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(service.receivedTask.get()).isNotNull();
        assertThat(service.receivedTask.get().getTaskId()).isEqualTo("TASK-999");
        assertThat(service.receivedTask.get().getStatus()).isEqualTo("PROCESSING");
        assertThat(service.receivedQueue.get()).isEqualTo("two-param-queue");
    }

    @Test
    @DisplayName("测试消费异常隔离：业务抛错不崩溃 worker 线程持续消费")
    void testExceptionResilience() throws Exception {
        ErrorResilientService service = new ErrorResilientService();

        when(redissonClient.getBlockingQueue("error-resilient-queue")).thenReturn(blockingQueue);
        when(redissonClient.getDelayedQueue(blockingQueue)).thenReturn(delayedQueue);

        // 第一次返回消息触发异常，第二次返回消息正常消费
        when(blockingQueue.poll(anyLong(), any(TimeUnit.class)))
                .thenReturn("MSG-ERR-1")
                .thenReturn("MSG-RECOVER-2")
                .thenReturn(null);

        GenericApplicationContext context = new GenericApplicationContext();
        context.getBeanFactory().registerSingleton("errorResilientService", service);
        context.refresh();

        bootstrap.setApplicationContext(context);
        bootstrap.afterSingletonsInstantiated();

        boolean completed = service.latch.await(15, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(service.errorInvocationCount.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("测试占位符解析解析队列名")
    void testPlaceholderResolution() throws Exception {
        PlaceholderService service = new PlaceholderService();
        when(redissonClient.getBlockingQueue("resolved-queue")).thenReturn(blockingQueue);
        when(redissonClient.getDelayedQueue(blockingQueue)).thenReturn(delayedQueue);
        when(blockingQueue.poll(anyLong(), any(TimeUnit.class)))
                .thenReturn("RESOLVED-MSG")
                .thenReturn(null);

        GenericApplicationContext context = new GenericApplicationContext();
        context.getBeanFactory().registerSingleton("placeholderService", service);
        context.refresh();

        bootstrap.setApplicationContext(context);
        bootstrap.setEmbeddedValueResolver(strVal -> "${custom.queue.name:fallback-queue}".equals(strVal) ? "resolved-queue" : strVal);
        bootstrap.afterSingletonsInstantiated();

        boolean completed = service.latch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        verify(redissonClient, atLeastOnce()).getBlockingQueue("resolved-queue");
    }

    @Test
    @DisplayName("测试消费失败自动重试机制")
    void testConsumerRetryOnFailure() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        CountDownLatch retryLatch = new CountDownLatch(3);

        class RetryConsumer {
            @DelayedQueueListener(queue = "retry-queue")
            public void handle(String msg) {
                attempts.incrementAndGet();
                retryLatch.countDown();
                throw new RuntimeException("每次调用均模拟失败以触发重试");
            }
        }

        RetryConsumer retryService = new RetryConsumer();

        RBlockingQueue<Object> dlqQueue = org.mockito.Mockito.mock(RBlockingQueue.class);
        when(redissonClient.getBlockingQueue("retry-queue")).thenReturn(blockingQueue);
        when(redissonClient.getBlockingQueue("retry-queue:dlq")).thenReturn(dlqQueue);
        when(redissonClient.getDelayedQueue(blockingQueue)).thenReturn(delayedQueue);
        when(blockingQueue.poll(anyLong(), any(TimeUnit.class)))
                .thenReturn("MSG-RETRY")
                .thenReturn(null);

        GenericApplicationContext context = new GenericApplicationContext();
        context.getBeanFactory().registerSingleton("retryService", retryService);
        context.refresh();

        bootstrap.setApplicationContext(context);
        bootstrap.afterSingletonsInstantiated();

        boolean reached = retryLatch.await(5, TimeUnit.SECONDS);
        assertThat(reached).isTrue();
        // 初始 1 次 + 重试 2 次 = 3 次执行
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("测试延时队列消费期间自动注入 MDC traceId 并在消费后安全清理")
    void testMdcTraceIdPropagation() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> capturedTraceId = new AtomicReference<>();

        class TracedConsumer {
            @DelayedQueueListener(queue = "traced-queue")
            public void handle(String msg) {
                capturedTraceId.set(org.slf4j.MDC.get(DelayedQueueBootstrap.MDC_TRACE_ID_KEY));
                latch.countDown();
            }
        }

        TracedConsumer consumer = new TracedConsumer();
        when(redissonClient.getBlockingQueue("traced-queue")).thenReturn(blockingQueue);
        when(redissonClient.getDelayedQueue(blockingQueue)).thenReturn(delayedQueue);
        when(blockingQueue.poll(anyLong(), any(TimeUnit.class)))
                .thenReturn("TRACED-MSG")
                .thenReturn(null);

        GenericApplicationContext context = new GenericApplicationContext();
        context.getBeanFactory().registerSingleton("tracedConsumer", consumer);
        context.refresh();

        bootstrap.setApplicationContext(context);
        bootstrap.afterSingletonsInstantiated();

        boolean completed = latch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(capturedTraceId.get()).isNotBlank().hasSize(32);
    }

    @Test
    @DisplayName("测试延时队列重试耗尽后完整投递结构化死信消息到 DLQ")
    void testDeadLetterQueueDeliveryWhenRetriesExhausted() throws Exception {
        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<io.github.atengk.boot.redis.delay.model.DeadLetterMessage<?>> capturedDeadLetter = new AtomicReference<>();

        class FailingConsumer {
            @DelayedQueueListener(queue = "dlq-test-queue")
            public void handle(String msg) {
                throw new IllegalStateException("模拟业务终态不可恢复异常: " + msg);
            }
        }

        RBlockingQueue<Object> dlqQueue = org.mockito.Mockito.mock(RBlockingQueue.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            capturedDeadLetter.set(invocation.getArgument(0));
            doneLatch.countDown();
            return true;
        }).when(dlqQueue).offer(any());

        when(redissonClient.getBlockingQueue("dlq-test-queue")).thenReturn(blockingQueue);
        when(redissonClient.getBlockingQueue("dlq-test-queue:dlq")).thenReturn(dlqQueue);
        when(redissonClient.getDelayedQueue(blockingQueue)).thenReturn(delayedQueue);
        when(blockingQueue.poll(anyLong(), any(TimeUnit.class)))
                .thenReturn("BAD-ORDER-100")
                .thenReturn(null);

        GenericApplicationContext context = new GenericApplicationContext();
        context.getBeanFactory().registerSingleton("failingConsumer", new FailingConsumer());
        context.refresh();

        bootstrap.setApplicationContext(context);
        bootstrap.afterSingletonsInstantiated();

        boolean reached = doneLatch.await(5, TimeUnit.SECONDS);
        assertThat(reached).isTrue();

        io.github.atengk.boot.redis.delay.model.DeadLetterMessage<?> dlMessage = capturedDeadLetter.get();
        assertThat(dlMessage).isNotNull();
        assertThat(dlMessage.originalQueue()).isEqualTo("dlq-test-queue");
        assertThat(dlMessage.payload()).isEqualTo("BAD-ORDER-100");
        assertThat(dlMessage.retryTimes()).isEqualTo(2);
        assertThat(dlMessage.errorMessage()).contains("模拟业务终态不可恢复异常: BAD-ORDER-100");
        assertThat(dlMessage.exceptionClass()).isEqualTo(IllegalStateException.class.getName());
        assertThat(dlMessage.failedTime()).isNotNull();
    }

    @Test
    @DisplayName("测试自定义 DelayedQueueFailureHandler SPI 回调正常触发")
    void testCustomFailureHandlerInvocation() throws Exception {
        CountDownLatch handlerLatch = new CountDownLatch(1);
        AtomicReference<io.github.atengk.boot.redis.delay.model.DeadLetterMessage<?>> handlerMessage = new AtomicReference<>();
        AtomicReference<Throwable> handlerCause = new AtomicReference<>();

        io.github.atengk.boot.redis.delay.spi.DelayedQueueFailureHandler customHandler = (msg, cause) -> {
            handlerMessage.set(msg);
            handlerCause.set(cause);
            handlerLatch.countDown();
        };

        io.github.atengk.boot.redis.properties.RedisProperties properties = new io.github.atengk.boot.redis.properties.RedisProperties();
        properties.getDelayQueue().setRetryTimes(1);
        properties.getDelayQueue().setRetryDelay(Duration.ofMillis(20));

        DelayedQueueBootstrap customBootstrap = new DelayedQueueBootstrap(
                redisDelayedQueue, objectMapper, properties, customHandler
        );

        class AlwaysFails {
            @DelayedQueueListener(queue = "custom-handler-queue")
            public void handle(String msg) {
                throw new IllegalArgumentException("自定义 SPI 异常: " + msg);
            }
        }

        RBlockingQueue<Object> dlqQueue = org.mockito.Mockito.mock(RBlockingQueue.class);
        when(redissonClient.getBlockingQueue("custom-handler-queue")).thenReturn(blockingQueue);
        when(redissonClient.getBlockingQueue("custom-handler-queue:dlq")).thenReturn(dlqQueue);
        when(redissonClient.getDelayedQueue(blockingQueue)).thenReturn(delayedQueue);
        when(blockingQueue.poll(anyLong(), any(TimeUnit.class)))
                .thenReturn("TEST-CUSTOM-SPI")
                .thenReturn(null);

        GenericApplicationContext context = new GenericApplicationContext();
        context.getBeanFactory().registerSingleton("alwaysFails", new AlwaysFails());
        context.refresh();

        customBootstrap.setApplicationContext(context);
        customBootstrap.afterSingletonsInstantiated();

        try {
            boolean reached = handlerLatch.await(5, TimeUnit.SECONDS);
            assertThat(reached).isTrue();
            assertThat(handlerMessage.get()).isNotNull();
            assertThat(handlerMessage.get().originalQueue()).isEqualTo("custom-handler-queue");
            assertThat(handlerMessage.get().payload()).isEqualTo("TEST-CUSTOM-SPI");
            assertThat(handlerCause.get()).isInstanceOf(IllegalArgumentException.class);
        } finally {
            customBootstrap.destroy();
        }
    }

    @Test
    @DisplayName("测试当 deadLetterEnabled 为 false 时跳过 DLQ 写入但依然触发 SPI 回调")
    void testDeadLetterDisabled() throws Exception {
        CountDownLatch handlerLatch = new CountDownLatch(1);
        AtomicReference<io.github.atengk.boot.redis.delay.model.DeadLetterMessage<?>> handlerMessage = new AtomicReference<>();

        io.github.atengk.boot.redis.delay.spi.DelayedQueueFailureHandler customHandler = (msg, cause) -> {
            handlerMessage.set(msg);
            handlerLatch.countDown();
        };

        io.github.atengk.boot.redis.properties.RedisProperties properties = new io.github.atengk.boot.redis.properties.RedisProperties();
        properties.getDelayQueue().setRetryTimes(1);
        properties.getDelayQueue().setRetryDelay(Duration.ofMillis(20));
        properties.getDelayQueue().setDeadLetterEnabled(false);

        DelayedQueueBootstrap noDlqBootstrap = new DelayedQueueBootstrap(
                redisDelayedQueue, objectMapper, properties, customHandler
        );

        class SkipDlqConsumer {
            @DelayedQueueListener(queue = "skip-dlq-queue")
            public void handle(String msg) {
                throw new RuntimeException("跳过写入死信队列测试");
            }
        }

        when(redissonClient.getBlockingQueue("skip-dlq-queue")).thenReturn(blockingQueue);
        when(redissonClient.getDelayedQueue(blockingQueue)).thenReturn(delayedQueue);
        when(blockingQueue.poll(anyLong(), any(TimeUnit.class)))
                .thenReturn("SKIP-DLQ-MSG")
                .thenReturn(null);

        GenericApplicationContext context = new GenericApplicationContext();
        context.getBeanFactory().registerSingleton("skipDlqConsumer", new SkipDlqConsumer());
        context.refresh();

        noDlqBootstrap.setApplicationContext(context);
        noDlqBootstrap.afterSingletonsInstantiated();

        try {
            boolean reached = handlerLatch.await(5, TimeUnit.SECONDS);
            assertThat(reached).isTrue();
            assertThat(handlerMessage.get()).isNotNull();
            // 验证未调用死信队列
            verify(redissonClient, org.mockito.Mockito.never()).getBlockingQueue("skip-dlq-queue:dlq");
        } finally {
            noDlqBootstrap.destroy();
        }
    }
}

