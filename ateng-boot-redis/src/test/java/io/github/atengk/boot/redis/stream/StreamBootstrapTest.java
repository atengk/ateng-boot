package io.github.atengk.boot.redis.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.redis.properties.RedisProperties;
import io.github.atengk.boot.redis.serializer.RedisSerializerFactory;
import io.github.atengk.boot.redis.stream.annotation.RedisStreamListener;
import io.github.atengk.boot.redis.stream.bootstrap.StreamBootstrap;
import io.github.atengk.boot.redis.stream.model.StreamDeadLetterMessage;
import io.github.atengk.boot.redis.stream.spi.StreamFailureHandler;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StreamBootstrap 容器调度器单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("StreamBootstrap 容器调度器单元测试")
class StreamBootstrapTest {

    private final RedisStreamTemplate streamTemplate = mock(RedisStreamTemplate.class);
    private final StreamFailureHandler failureHandler = mock(StreamFailureHandler.class);
    private final ObjectMapper objectMapper = RedisSerializerFactory.createSafeObjectMapper();

    private RedisProperties properties;
    private StreamBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        properties = new RedisProperties();
        properties.getStream().setPollTimeout(Duration.ofMillis(50));
        properties.getStream().setRetryTimes(2);
        bootstrap = new StreamBootstrap(streamTemplate, objectMapper, properties, failureHandler);
    }

    @AfterEach
    void tearDown() {
        if (bootstrap != null) {
            bootstrap.destroy();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class OrderEvent {
        private String orderId;
        private Double amount;
    }

    static class SampleOrderService {
        final List<OrderEvent> receivedOrders = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);

        @RedisStreamListener(stream = "order-stream", group = "order-group", concurrency = 1, autoAck = true)
        public void handleOrder(OrderEvent event) {
            receivedOrders.add(event);
            latch.countDown();
        }
    }

    static class FailingOrderService {
        final AtomicInteger attemptCount = new AtomicInteger(0);
        final CountDownLatch latch = new CountDownLatch(3);

        @RedisStreamListener(stream = "fail-stream", group = "fail-group", concurrency = 1, autoAck = true, retryTimes = 2)
        public void handleFailingOrder(OrderEvent event) {
            attemptCount.incrementAndGet();
            latch.countDown();
            throw new RuntimeException("模拟业务处理失败");
        }
    }

    @Test
    @DisplayName("测试正常消费流程：扫描监听器方法、自愈建组、反序列化参数并自动确认 XACK")
    void testSuccessfulConsumptionWithAutoAck() throws Exception {
        SampleOrderService service = new SampleOrderService();

        ApplicationContext context = mock(ApplicationContext.class);
        ConfigurableListableBeanFactory beanFactory = mock(ConfigurableListableBeanFactory.class);
        when(context.getAutowireCapableBeanFactory()).thenReturn(beanFactory);
        when(beanFactory.getBeanDefinitionNames()).thenReturn(new String[]{"sampleOrderService"});
        when(beanFactory.getType("sampleOrderService")).thenAnswer(inv -> SampleOrderService.class);
        when(context.getBean("sampleOrderService")).thenReturn(service);
        when(context.getType("sampleOrderService")).thenAnswer(inv -> SampleOrderService.class);
        when(context.containsBean("sampleOrderService")).thenReturn(true);

        when(streamTemplate.formatStreamKey("order-stream")).thenReturn("order-stream");

        Map<String, Object> map = new HashMap<>();
        map.put("payload", new OrderEvent("ORD_SUCCESS_1", 88.0));
        map.put("traceId", "mock-trace-999");
        MapRecord<String, Object, Object> record = MapRecord.create("order-stream", (Map) map)
                .withId(RecordId.of("100-0"));

        // 第一次拉取返回 1 条数据，后续返回空
        AtomicInteger pollCount = new AtomicInteger(0);
        when(streamTemplate.read(any(), any(), any())).thenAnswer(inv -> {
            if (pollCount.getAndIncrement() == 0) {
                return List.of(record);
            }
            return Collections.emptyList();
        });

        bootstrap.setApplicationContext(context);
        bootstrap.afterSingletonsInstantiated();

        boolean received = service.latch.await(3, TimeUnit.SECONDS);
        assertThat(received).isTrue();
        assertThat(service.receivedOrders).hasSize(1);
        assertThat(service.receivedOrders.get(0).getOrderId()).isEqualTo("ORD_SUCCESS_1");

        verify(streamTemplate, timeout(3000).atLeastOnce()).createGroup(eq("order-stream"), eq("order-group"), any(), eq(true));
        verify(streamTemplate, timeout(3000).atLeastOnce()).acknowledge("order-stream", "order-group", RecordId.of("100-0"));
    }

    @Test
    @DisplayName("测试失败重试耗尽流程：触发 transferToDeadLetter 转移至死信并回调 StreamFailureHandler")
    void testRetryExhaustionAndDeadLetterTransfer() throws Exception {
        FailingOrderService failingService = new FailingOrderService();
        CountDownLatch deadLetterLatch = new CountDownLatch(1);
        doAnswer(inv -> {
            deadLetterLatch.countDown();
            return null;
        }).when(failureHandler).onFailure(any(StreamDeadLetterMessage.class));

        ApplicationContext context = mock(ApplicationContext.class);
        ConfigurableListableBeanFactory beanFactory = mock(ConfigurableListableBeanFactory.class);
        when(context.getAutowireCapableBeanFactory()).thenReturn(beanFactory);
        when(beanFactory.getBeanDefinitionNames()).thenReturn(new String[]{"failingOrderService"});
        when(beanFactory.getType("failingOrderService")).thenAnswer(inv -> FailingOrderService.class);
        when(context.getBean("failingOrderService")).thenReturn(failingService);
        when(context.getType("failingOrderService")).thenAnswer(inv -> FailingOrderService.class);
        when(context.containsBean("failingOrderService")).thenReturn(true);

        when(streamTemplate.formatStreamKey("fail-stream")).thenReturn("fail-stream");

        Map<String, Object> map = new HashMap<>();
        map.put("payload", new OrderEvent("ORD_FAIL_99", 19.9));
        MapRecord<String, Object, Object> record = MapRecord.create("fail-stream", (Map) map)
                .withId(RecordId.of("200-0"));

        AtomicInteger pollCount = new AtomicInteger(0);
        when(streamTemplate.read(any(), any(), any())).thenAnswer(inv -> {
            if (pollCount.getAndIncrement() == 0) {
                return List.of(record);
            }
            return Collections.emptyList();
        });

        when(streamTemplate.transferToDeadLetter(any(), any(), any(), any(), any(int.class), any()))
                .thenReturn(RecordId.of("999-0"));

        bootstrap.setApplicationContext(context);
        bootstrap.afterSingletonsInstantiated();

        boolean reachedAttempts = failingService.latch.await(3, TimeUnit.SECONDS);
        assertThat(reachedAttempts).isTrue();
        boolean deadLetterTriggered = deadLetterLatch.await(3, TimeUnit.SECONDS);
        assertThat(deadLetterTriggered).isTrue();
        assertThat(failingService.attemptCount.get()).isGreaterThanOrEqualTo(3);

        verify(streamTemplate, timeout(3000).atLeastOnce()).transferToDeadLetter(
                eq("fail-stream"), eq("fail-group"), any(), any(), any(int.class), any());
        verify(failureHandler, timeout(3000).atLeastOnce()).onFailure(any(StreamDeadLetterMessage.class));
    }
}
