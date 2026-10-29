package io.github.atengk.boot.rocketmq.template;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.MessageDeliveryException;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.rocketmq.consumer.SimpleConsumerFactory;
import io.github.atengk.boot.rocketmq.properties.AtengRocketmqProperties;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RocketmqSimpleConsumerTemplate 单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
@DisplayName("RocketmqSimpleConsumerTemplate 流式拉取门面单元测试")
class RocketmqSimpleConsumerTemplateTest {

    private AtengRocketmqProperties properties;
    private ClientConfiguration clientConfiguration;
    private SimpleConsumerFactory simpleConsumerFactory;
    private SimpleConsumer mockConsumer;
    private RocketmqSimpleConsumerTemplate template;

    @BeforeEach
    void setUp() throws Exception {
        properties = new AtengRocketmqProperties();
        clientConfiguration = mock(ClientConfiguration.class);
        simpleConsumerFactory = mock(SimpleConsumerFactory.class);
        mockConsumer = mock(SimpleConsumer.class);

        when(simpleConsumerFactory.createSimpleConsumer(any(), any(), any(), any()))
                .thenReturn(mockConsumer);

        template = new RocketmqSimpleConsumerTemplate(properties, clientConfiguration, simpleConsumerFactory);
    }

    @AfterEach
    void tearDown() {
        template.destroy();
        MDC.clear();
        TenantContextHolder.clear();
    }

    private MessageView createMockMessageView(String topic, String msgId, byte[] body, Map<String, String> properties) {
        MessageView mv = mock(MessageView.class);
        MessageId messageId = mock(MessageId.class);
        when(messageId.toString()).thenReturn(msgId);
        when(mv.getMessageId()).thenReturn(messageId);
        when(mv.getTopic()).thenReturn(topic);
        when(mv.getBody()).thenReturn(ByteBuffer.wrap(body));
        when(mv.getProperties()).thenReturn(properties != null ? properties : Collections.emptyMap());
        when(mv.getTag()).thenReturn(Optional.of("tagA"));
        when(mv.getKeys()).thenReturn(List.of("key-" + msgId));
        return mv;
    }

    @Test
    @DisplayName("验证同步拉取消息成功：正常返回列表并追踪 Group 映射")
    void testReceiveSyncSuccess() throws Exception {
        MessageView msg1 = createMockMessageView("order_topic", "MSG-001", "hello".getBytes(StandardCharsets.UTF_8), null);
        MessageView msg2 = createMockMessageView("order_topic", "MSG-002", "world".getBytes(StandardCharsets.UTF_8), null);

        when(mockConsumer.receive(eq(10), any(Duration.class))).thenReturn(List.of(msg1, msg2));

        List<MessageView> result = template.receive("order_topic", "order_group", 10, Duration.ofSeconds(15));

        assertThat(result).hasSize(2);
        verify(mockConsumer).receive(10, Duration.ofSeconds(15));
    }

    @Test
    @DisplayName("验证异步拉取消息成功：返回 CompletableFuture")
    void testReceiveAsyncSuccess() throws Exception {
        MessageView msg1 = createMockMessageView("order_topic", "MSG-003", "data".getBytes(StandardCharsets.UTF_8), null);
        when(mockConsumer.receiveAsync(eq(5), any(Duration.class)))
                .thenReturn(CompletableFuture.completedFuture(List.of(msg1)));

        CompletableFuture<List<MessageView>> future = template.receiveAsync("order_topic", "order_group", 5, Duration.ofSeconds(10));
        List<MessageView> result = future.get();

        assertThat(result).hasSize(1);
        verify(mockConsumer).receiveAsync(5, Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("验证拉取入参防卫校验：非法 Topic、非正数批次或空租约抛出异常")
    void testReceiveValidation() {
        assertThatThrownBy(() -> template.receive("illegal.topic.with.dots", "group"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Topic 名称");

        assertThatThrownBy(() -> template.receive("order_topic", "group", 0, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxMessageNum 必须大于 0");

        assertThatThrownBy(() -> template.receive("order_topic", "group", 10, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invisibleDuration 必须大于 0");
    }

    @Test
    @DisplayName("验证消息确认 (ack / ackAsync)：正常调用底层客户端")
    void testAckOperations() throws Exception {
        MessageView msg = createMockMessageView("order_topic", "MSG-ACK-1", "test".getBytes(StandardCharsets.UTF_8), null);
        when(mockConsumer.receive(eq(1), any(Duration.class))).thenReturn(List.of(msg));
        template.receive("order_topic", "order_group", 1, Duration.ofSeconds(10));

        // 验证自动根据跟踪映射 ack
        template.ack(msg);
        verify(mockConsumer).ack(msg);

        // 验证显式指定 group 的 ackAsync
        when(mockConsumer.ackAsync(msg)).thenReturn(CompletableFuture.completedFuture(null));
        CompletableFuture<Void> ackFuture = template.ackAsync("order_group", msg);
        ackFuture.get();
        verify(mockConsumer).ackAsync(msg);
    }

    @Test
    @DisplayName("验证租约续期 (changeInvisibleDuration / changeInvisibleDurationAsync)：正常调用底层客户端")
    void testChangeInvisibleDurationOperations() throws Exception {
        MessageView msg = createMockMessageView("order_topic", "MSG-RENEW-1", "test".getBytes(StandardCharsets.UTF_8), null);
        when(mockConsumer.receive(eq(1), any(Duration.class))).thenReturn(List.of(msg));
        template.receive("order_topic", "order_group", 1, Duration.ofSeconds(10));

        template.changeInvisibleDuration(msg, Duration.ofSeconds(45));
        verify(mockConsumer).changeInvisibleDuration(msg, Duration.ofSeconds(45));

        when(mockConsumer.changeInvisibleDurationAsync(msg, Duration.ofSeconds(60)))
                .thenReturn(CompletableFuture.completedFuture(null));
        CompletableFuture<Void> renewFuture = template.changeInvisibleDurationAsync("order_group", msg, Duration.ofSeconds(60));
        renewFuture.get();
        verify(mockConsumer).changeInvisibleDurationAsync(msg, Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("验证高阶执行闭包 (receiveAndExecute)：推断反序列化、四维上下文自动透传与自动 ACK")
    void testReceiveAndExecuteString() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put(MessageHeaderConstants.TRACE_ID, "trace-simple-888");
        headers.put(MessageHeaderConstants.TENANT_ID, "tenant-simple-999");

        MessageView msg = createMockMessageView("order_topic", "MSG-EXEC-1", "order-payload-content".getBytes(StandardCharsets.UTF_8), headers);
        when(mockConsumer.receive(eq(5), any(Duration.class))).thenReturn(List.of(msg));

        AtomicReference<String> capturedPayload = new AtomicReference<>();
        AtomicReference<String> capturedTraceId = new AtomicReference<>();
        AtomicReference<String> capturedTenantId = new AtomicReference<>();

        int count = template.receiveAndExecute("order_topic", "order_group", 5, Duration.ofSeconds(20), String.class, item -> {
            capturedPayload.set(item);
            capturedTraceId.set(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY));
            capturedTenantId.set(TenantContextHolder.getTenantId());
        });

        assertThat(count).isEqualTo(1);
        assertThat(capturedPayload.get()).isEqualTo("order-payload-content");
        assertThat(capturedTraceId.get()).isEqualTo("trace-simple-888");
        assertThat(capturedTenantId.get()).isEqualTo("tenant-simple-999");

        // 验证执行完成后上下文已严格清理
        assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();

        // 验证自动触发了 ACK
        verify(mockConsumer).ack(msg);
    }

    @Test
    @DisplayName("验证高阶执行闭包 (receiveAndExecute)：JSON 对象反序列化正确")
    void testReceiveAndExecuteJson() throws Exception {
        Map<String, Object> data = Map.of("name", "Ateng", "code", 200);
        byte[] jsonBytes = JsonUtil.toJsonBytes(data);

        MessageView msg = createMockMessageView("order_topic", "MSG-EXEC-2", jsonBytes, null);
        when(mockConsumer.receive(eq(5), any(Duration.class))).thenReturn(List.of(msg));

        AtomicInteger processed = new AtomicInteger(0);
        int count = template.receiveAndExecute("order_topic", "order_group", 5, Duration.ofSeconds(20), Map.class, item -> {
            processed.incrementAndGet();
            assertThat(item.get("name")).isEqualTo("Ateng");
        });

        assertThat(count).isEqualTo(1);
        assertThat(processed.get()).isEqualTo(1);
        verify(mockConsumer).ack(msg);
    }

    @Test
    @DisplayName("验证客户端缓存复用：同一 ConsumerGroup 仅初始化一次，追加订阅不同 Topic")
    void testConsumerCacheReusesClientPerGroup() throws Exception {
        when(mockConsumer.getSubscriptionExpressions()).thenReturn(Map.of("order_topic", mock(FilterExpression.class)));

        template.receive("order_topic", "common_group", 5, Duration.ofSeconds(10));
        template.receive("pay_topic", "common_group", 5, Duration.ofSeconds(10));

        // 验证工厂只创建了一次该 Group 的 SimpleConsumer
        verify(simpleConsumerFactory, times(1)).createSimpleConsumer(any(), eq("common_group"), any(), any());
        // 验证对新 Topic 发起了追加订阅
        verify(mockConsumer).subscribe(eq("pay_topic"), any(FilterExpression.class));
    }

    @Test
    @DisplayName("验证 DisposableBean：destroy() 优雅关闭所有缓存的 SimpleConsumer")
    void testDestroyClosesAllConsumers() throws Exception {
        template.receive("order_topic", "group_a", 5, Duration.ofSeconds(10));
        template.receive("order_topic", "group_b", 5, Duration.ofSeconds(10));

        template.destroy();

        verify(mockConsumer, times(2)).close();

        // 销毁后再操作将抛出 IllegalStateException
        assertThatThrownBy(() -> template.receive("order_topic", "group_c", 5, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已关闭");
    }
}
