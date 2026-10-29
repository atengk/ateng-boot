package io.github.atengk.boot.rabbitmq.template;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.MessageDeliveryException;
import io.github.atengk.boot.rabbitmq.properties.RabbitmqProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RabbitReliableTemplate 批量可靠投递 (sendBatchReliable) 单元测试
 * 100% 离线自洽，验证批量消息独立 CorrelationData 确认、聚合等待、部分失败/超时熔断与前置 Fail-Fast 防卫
 *
 * @author Ateng
 * @since 2026-10-29
 */
@DisplayName("RabbitReliableTemplate 批量可靠投递单元测试")
class RabbitBatchReliableTemplateTest {

    private RabbitTemplate rabbitTemplate;
    private RabbitmqProperties properties;
    private RabbitReliableTemplate template;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        when(rabbitTemplate.getMessageConverter()).thenReturn(new Jackson2JsonMessageConverter());

        properties = new RabbitmqProperties();
        properties.getReliable().setDefaultTimeout(Duration.ofMillis(300));

        template = new RabbitReliableTemplate(rabbitTemplate, properties);
    }

    @AfterEach
    void tearDown() {
        // 遵循上下文零污染铁律：重置四维上下文现场
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("批量可靠投递：所有消息均收到 Broker ACK 时正常成功返回，且每条消息拥有独立 CorrelationData 与上下文")
    void testSendBatchReliableAllAckSuccess() {
        MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "trace-batch-001");
        UserContextHolder.set("user-batch-01");
        TenantContextHolder.setTenantId("tenant-batch-01");

        List<String> payloads = List.of("item-1", "item-2", "item-3");
        List<CorrelationData> capturedCds = Collections.synchronizedList(new ArrayList<>());
        List<Message> capturedMessages = Collections.synchronizedList(new ArrayList<>());

        doAnswer(invocation -> {
            Message msg = invocation.getArgument(2);
            CorrelationData cd = invocation.getArgument(3);
            capturedMessages.add(msg);
            capturedCds.add(cd);
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(eq("batch.exchange"), eq("batch.key"), any(Message.class), any(CorrelationData.class));

        template.sendBatchReliable("batch.exchange", "batch.key", payloads);

        verify(rabbitTemplate, times(3)).send(eq("batch.exchange"), eq("batch.key"), any(Message.class), any(CorrelationData.class));
        assertThat(capturedCds).hasSize(3);

        // 验证每条消息的 CorrelationData ID 均独立唯一且包含 trace/bizKey
        for (int i = 0; i < 3; i++) {
            CorrelationData cd = capturedCds.get(i);
            assertThat(cd.getId()).isNotBlank();
            Message msg = capturedMessages.get(i);
            assertThat((String) msg.getMessageProperties().getHeader(MessageHeaderConstants.TRACE_ID)).isEqualTo("trace-batch-001");
            assertThat((String) msg.getMessageProperties().getHeader(MessageHeaderConstants.USER_ID)).isEqualTo("user-batch-01");
            assertThat((String) msg.getMessageProperties().getHeader(MessageHeaderConstants.TENANT_ID)).isEqualTo("tenant-batch-01");
        }
    }

    @Test
    @DisplayName("批量可靠投递：部分消息被 Broker NACK 时抛出 MessageDeliveryException 并聚合报错明细")
    void testSendBatchReliablePartialNackThrowsException() {
        List<String> payloads = List.of("item-0", "item-1", "item-2");
        AtomicInteger counter = new AtomicInteger(0);

        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            int idx = counter.getAndIncrement();
            if (idx == 1) {
                // 第 2 条消息返回 NACK
                cd.getFuture().complete(new CorrelationData.Confirm(false, "Queue quota exceeded"));
            } else {
                cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            }
            return null;
        }).when(rabbitTemplate).send(eq("batch.exchange"), eq("batch.key"), any(Message.class), any(CorrelationData.class));

        assertThatThrownBy(() -> template.sendBatchReliable("batch.exchange", "batch.key", payloads))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("RabbitMQ 批量消息部分发送被 Broker 拒绝或异常")
                .hasMessageContaining("index=1")
                .hasMessageContaining("Queue quota exceeded")
                .satisfies(ex -> {
                    MessageDeliveryException mde = (MessageDeliveryException) ex;
                    assertThat(mde.getMqType()).isEqualTo("rabbitmq");
                    assertThat(mde.getTopic()).isEqualTo("batch.exchange");
                    assertThat(mde.getBizKey()).isEqualTo("batch[3]");
                });
    }

    @Test
    @DisplayName("批量可靠投递：消息等待 Broker ACK 超时抛出 MessageDeliveryException")
    void testSendBatchReliableTimeoutThrowsException() {
        List<String> payloads = List.of("item-fast", "item-slow");

        doAnswer(invocation -> {
            Message msg = invocation.getArgument(2);
            CorrelationData cd = invocation.getArgument(3);
            String body = new String(msg.getBody());
            if (body.contains("item-fast")) {
                cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            }
            // item-slow 不 complete future，模拟挂起超时
            return null;
        }).when(rabbitTemplate).send(eq("batch.exchange"), eq("batch.key"), any(Message.class), any(CorrelationData.class));

        assertThatThrownBy(() -> template.sendBatchReliable("batch.exchange", "batch.key", payloads, Duration.ofMillis(50)))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("RabbitMQ 批量消息发送等待 Broker ACK 超时")
                .hasMessageContaining("status=TIMEOUT")
                .satisfies(ex -> {
                    MessageDeliveryException mde = (MessageDeliveryException) ex;
                    assertThat(mde.getMqType()).isEqualTo("rabbitmq");
                    assertThat(mde.getTopic()).isEqualTo("batch.exchange");
                    assertThat(mde.getBizKey()).isEqualTo("batch[2]");
                });
    }

    @Test
    @DisplayName("批量可靠投递：底层网络发信抛出 AmqpException 时聚合捕获并转译 MessageDeliveryException")
    void testSendBatchReliableNetworkErrorAsyncThrowsException() {
        List<String> payloads = List.of("item-good", "item-bad");

        doAnswer(invocation -> {
            Message msg = invocation.getArgument(2);
            CorrelationData cd = invocation.getArgument(3);
            String body = new String(msg.getBody());
            if (body.contains("item-good")) {
                cd.getFuture().complete(new CorrelationData.Confirm(true, null));
                return null;
            } else {
                throw new AmqpException("Broker connection disconnected");
            }
        }).when(rabbitTemplate).send(eq("batch.exchange"), eq("batch.key"), any(Message.class), any(CorrelationData.class));

        assertThatThrownBy(() -> template.sendBatchReliable("batch.exchange", "batch.key", payloads))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("RabbitMQ 批量消息部分发送被 Broker 拒绝或异常")
                .hasMessageContaining("Broker connection disconnected")
                .hasRootCauseInstanceOf(AmqpException.class)
                .hasRootCauseMessage("Broker connection disconnected");
    }

    @Test
    @DisplayName("批量可靠投递：等待 ACK 被中断时恢复线程中断标记并抛出 MessageDeliveryException")
    void testSendBatchReliableInterruptedThrowsException() {
        List<String> payloads = List.of("item-interrupt");

        doNothing().when(rabbitTemplate).send(any(), any(), any(), any());

        Thread.currentThread().interrupt();

        try {
            assertThatThrownBy(() -> template.sendBatchReliable("batch.exchange", "batch.key", payloads))
                    .isInstanceOf(MessageDeliveryException.class)
                    .hasMessageContaining("RabbitMQ 批量消息发送等待 Broker ACK 被中断");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // 遵循零污染铁律：清除当前线程中断状态
        }
    }

    @Test
    @DisplayName("批量前置防卫：入参空校验与空元素 Fail-Fast 阻断，绝对零网络调用")
    void testSendBatchReliablePreflightValidation() {
        // 1. payloads 为 null
        assertThatThrownBy(() -> template.sendBatchReliable("batch.exchange", "batch.key", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payloads 消息载荷列表不能为空");

        // 2. payloads 为空集合
        assertThatThrownBy(() -> template.sendBatchReliable("batch.exchange", "batch.key", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payloads 消息载荷列表不能为空列表");

        // 3. payloads 包含 null 元素
        List<String> listWithNull = new ArrayList<>();
        listWithNull.add("item-1");
        listWithNull.add(null);
        assertThatThrownBy(() -> template.sendBatchReliable("batch.exchange", "batch.key", listWithNull))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payloads[1] 消息载荷不能为 null");

        // 4. exchange 为空
        assertThatThrownBy(() -> template.sendBatchReliable("", "batch.key", List.of("item-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exchange 交换机名称不能为空");

        // 5. payloads 超过单批次上限 (1000)
        List<String> oversizedList = Collections.nCopies(1001, "item");
        assertThatThrownBy(() -> template.sendBatchReliable("batch.exchange", "batch.key", oversizedList))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payloads 批量投递数量不能超过 1000 条");

        // 验证以上所有校验失败时绝对零网络调用
        verify(rabbitTemplate, never()).send(any(), any(), any(), any());
    }

    @Test
    @DisplayName("批量异步可靠投递：sendBatchReliableAsync 非阻塞执行并成功聚合返回 CompletableFuture")
    void testSendBatchReliableAsyncSuccess() {
        List<String> payloads = List.of("async-1", "async-2");

        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(eq("batch.exchange"), eq("batch.key"), any(Message.class), any(CorrelationData.class));

        java.util.concurrent.CompletableFuture<Void> future =
                template.sendBatchReliableAsync("batch.exchange", "batch.key", payloads);

        assertThat(future).isNotNull();
        future.join(); // 必须成功完成且无异常

        verify(rabbitTemplate, times(2)).send(eq("batch.exchange"), eq("batch.key"), any(Message.class), any(CorrelationData.class));
    }

    @Test
    @DisplayName("批量前置防卫：批次中单条消息体积超出 maxPayloadSize 时 Fail-Fast 阻断，零网络调用")
    void testSendBatchReliablePayloadSizeExceededFailFast() {
        properties.getProducer().setMaxPayloadSize(DataSize.ofKilobytes(5)); // 5KB 限制

        List<Object> payloads = List.of(
                "normal-item",
                new byte[6 * 1024] // 6KB，超出限制
        );

        assertThatThrownBy(() -> template.sendBatchReliable("batch.exchange", "batch.key", payloads))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("出站消息报文体积超出最大限制");

        // 关键断言：一旦前置预校验失败，整批全部拦截，绝对零网络发信！
        verify(rabbitTemplate, never()).send(any(), any(), any(), any());
    }

    @Test
    @DisplayName("批量前置防卫：自定义 maxBatchSize 时超出阈值触发 Fail-Fast 阻断")
    void testSendBatchReliableCustomMaxBatchSizeExceeded() {
        properties.getProducer().setMaxBatchSize(50);

        List<String> oversizedList = Collections.nCopies(51, "item");
        assertThatThrownBy(() -> template.sendBatchReliable("batch.exchange", "batch.key", oversizedList))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payloads 批量投递数量不能超过 50 条");

        verify(rabbitTemplate, never()).send(any(), any(), any(), any());
    }
}
