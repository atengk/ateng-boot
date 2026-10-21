package io.github.atengk.boot.rabbitmq.template;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.MessageDeliveryException;
import io.github.atengk.boot.rabbitmq.callback.RabbitTransactionFailureCallback;
import io.github.atengk.boot.rabbitmq.properties.RabbitmqProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.postprocessor.GUnzipPostProcessor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RabbitReliableTemplate 生产级可靠投递与事务感知门面单元测试
 * 100% 离线自洽，基于 Mockito 与 Spring AMQP 原生对象验证 Confirm/Return/Timeout/GZIP/事务防幽灵发信
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("RabbitReliableTemplate 可靠投递与事务发信单元测试")
class RabbitReliableTemplateTest {

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
        // 遵循上下文零污染铁律：重置四维上下文与事务同步现场
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    @DisplayName("同步可靠发送：Broker 返回 ACK 时成功返回，并正确注入四维上下文与自适应 bizKey")
    void testSendReliableAckSuccess() {
        MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "trace-1001");
        UserContextHolder.set("user-999");
        TenantContextHolder.setTenantId("tenant-001");

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        ArgumentCaptor<CorrelationData> correlationCaptor = ArgumentCaptor.forClass(CorrelationData.class);

        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(eq("test.exchange"), eq("test.key"), messageCaptor.capture(), correlationCaptor.capture());

        template.sendReliable("test.exchange", "test.key", "Hello RabbitMQ", "biz-order-123");

        verify(rabbitTemplate, times(1)).send(eq("test.exchange"), eq("test.key"), any(), any());

        Message sentMessage = messageCaptor.getValue();
        MessageProperties props = sentMessage.getMessageProperties();
        assertThat((String) props.getHeader(MessageHeaderConstants.TRACE_ID)).isEqualTo("trace-1001");
        assertThat((String) props.getHeader(MessageHeaderConstants.USER_ID)).isEqualTo("user-999");
        assertThat((String) props.getHeader(MessageHeaderConstants.TENANT_ID)).isEqualTo("tenant-001");
        assertThat((String) props.getHeader(MessageHeaderConstants.BIZ_KEY)).isEqualTo("biz-order-123");
        assertThat((Object) props.getHeader(MessageHeaderConstants.SEND_TIMESTAMP)).isNotNull();

        CorrelationData correlationData = correlationCaptor.getValue();
        assertThat(correlationData.getId()).startsWith("biz-order-123:");
    }

    @Test
    @DisplayName("同步可靠发送：Broker 返回 NACK 时语义化转译抛出 MessageDeliveryException")
    void testSendReliableNackThrowsException() {
        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(false, "Queue overflow"));
            return null;
        }).when(rabbitTemplate).send(eq("test.exchange"), eq("test.key"), any(Message.class), any(CorrelationData.class));

        assertThatThrownBy(() -> template.sendReliable("test.exchange", "test.key", "test-payload", "biz-nack-1"))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("RabbitMQ 消息发送被 Broker NACK 拒绝")
                .hasMessageContaining("Queue overflow")
                .satisfies(ex -> {
                    MessageDeliveryException mde = (MessageDeliveryException) ex;
                    assertThat(mde.getMqType()).isEqualTo("rabbitmq");
                    assertThat(mde.getTopic()).isEqualTo("test.exchange");
                    assertThat(mde.getBizKey()).isEqualTo("biz-nack-1");
                });
    }

    @Test
    @DisplayName("同步可靠发送：Broker 等待 ACK 超时抛出 MessageDeliveryException")
    void testSendReliableTimeoutThrowsException() {
        // 不 complete future，模拟网络挂起超时
        doNothing().when(rabbitTemplate).send(eq("test.exchange"), eq("test.key"), any(Message.class), any(CorrelationData.class));

        assertThatThrownBy(() -> template.sendReliable("test.exchange", "test.key", "test-payload", "biz-timeout-1", Duration.ofMillis(50)))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("RabbitMQ 消息发送等待 Broker ACK 超时")
                .satisfies(ex -> {
                    MessageDeliveryException mde = (MessageDeliveryException) ex;
                    assertThat(mde.getMqType()).isEqualTo("rabbitmq");
                    assertThat(mde.getTopic()).isEqualTo("test.exchange");
                    assertThat(mde.getBizKey()).isEqualTo("biz-timeout-1");
                });
    }

    @Test
    @DisplayName("异步可靠发送：返回 CompletableFuture 并在 ACK 到达时正常完成")
    void testSendReliableAsync() {
        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(eq("async.exchange"), eq("async.key"), any(Message.class), any(CorrelationData.class));

        CompletableFuture<CorrelationData.Confirm> future = template.sendReliableAsync("async.exchange", "async.key", "async-payload", "biz-async-1");

        assertThat(future).isCompleted();
        CorrelationData.Confirm confirm = future.join();
        assertThat(confirm.isAck()).isTrue();
    }

    @Test
    @DisplayName("异步可靠发送：底层网络发生 AmqpException 时 Future 异常完成")
    void testSendReliableAsyncNetworkError() {
        doThrow(new AmqpException("Network unreachable"))
                .when(rabbitTemplate).send(eq("async.exchange"), eq("async.key"), any(Message.class), any(CorrelationData.class));

        CompletableFuture<CorrelationData.Confirm> future = template.sendReliableAsync("async.exchange", "async.key", "async-payload", "biz-async-fail");

        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(future::join)
                .hasCauseInstanceOf(MessageDeliveryException.class)
                .hasRootCauseInstanceOf(AmqpException.class)
                .hasRootCauseMessage("Network unreachable");
    }

    @Test
    @DisplayName("延迟消息发送：成功注入 x-delay 消息头并完成可靠投递")
    void testSendDelayed() {
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);

        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(eq("delay.exchange"), eq("delay.key"), messageCaptor.capture(), any(CorrelationData.class));

        template.sendDelayed("delay.exchange", "delay.key", "order-cancel-payload", "order-10086", Duration.ofSeconds(30));

        Message sentMessage = messageCaptor.getValue();
        assertThat((Long) sentMessage.getMessageProperties().getHeader("x-delay")).isEqualTo(30000L);
    }

    @Test
    @DisplayName("延迟消息发送：延迟为负数时 Fail-Fast 抛出 IllegalArgumentException")
    void testSendDelayedNegativeThrowsException() {
        assertThatThrownBy(() -> template.sendDelayed("delay.exchange", "delay.key", "payload", Duration.ofSeconds(-5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delay 延迟时间不能为负数");
    }

    @Test
    @DisplayName("大报文防爆：超过 2MB (maxPayloadSize) 消息在出站前 Fail-Fast 阻断抛出异常")
    void testMaxPayloadSizeEnforcement() {
        properties.getProducer().setMaxPayloadSize(DataSize.ofKilobytes(10)); // 临时收敛至 10KB 方便极速测试

        byte[] largeBytes = new byte[15 * 1024]; // 15KB

        assertThatThrownBy(() -> template.sendReliable("test.exchange", "test.key", largeBytes, "large-biz-1"))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("出站消息报文体积超出最大限制");

        // 校验底层完全零网络调用
        verify(rabbitTemplate, never()).send(any(), any(), any(), any());
    }

    @Test
    @DisplayName("透明 GZIP 压缩：超过 2KB 自动开启压缩并打标 Content-Encoding: gzip，入站透明无损解压还原")
    void testTransparentGzipCompression() {
        properties.getProducer().setCompressionThreshold(DataSize.ofKilobytes(2)); // 2KB

        // 构建重复文本产生可观压缩率的 4KB 字符串
        String rawText = "ateng-boot-rabbitmq-scalable-high-throughput-enterprise-messaging-".repeat(60);
        assertThat(rawText.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(2048);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);

        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(eq("test.exchange"), eq("test.key"), messageCaptor.capture(), any(CorrelationData.class));

        template.sendReliable("test.exchange", "test.key", rawText, "biz-gzip-1");

        Message compressedMessage = messageCaptor.getValue();
        assertThat(compressedMessage.getMessageProperties().getContentEncoding()).contains("gzip");
        assertThat(compressedMessage.getBody().length).isLessThan(rawText.getBytes(StandardCharsets.UTF_8).length);

        // 验证 Spring AMQP GUnzipPostProcessor 能透明无损解压还原
        GUnzipPostProcessor decompressor = new GUnzipPostProcessor();
        Message decompressedMessage = decompressor.postProcessMessage(compressedMessage);
        assertThat(decompressedMessage.getMessageProperties().getContentEncoding()).isEqualTo("UTF-8");
        assertThat(new String(decompressedMessage.getBody(), StandardCharsets.UTF_8)).isEqualTo("\"" + rawText + "\"");
    }

    @Test
    @DisplayName("sendAfterCommit 事务感知：在活跃事务中等待 afterCommit 提交后才物理发信，回滚时绝对零发信")
    void testSendAfterCommitWithActiveTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(eq("tx.exchange"), eq("tx.key"), any(Message.class), any(CorrelationData.class));

        // 1. 调用 sendAfterCommit，此时处于活跃事务中
        template.sendAfterCommit("tx.exchange", "tx.key", "tx-order-payload", "biz-tx-1");

        // 2. 验证此时尚未物理发信 (零网络调用，杜绝幽灵消息)
        verify(rabbitTemplate, never()).send(any(), any(), any(), any());

        // 3. 模拟数据库事务 Commit，触发 afterCommit 回调
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }

        // 4. 验证此时执行了物理发信
        verify(rabbitTemplate, times(1)).send(eq("tx.exchange"), eq("tx.key"), any(Message.class), any(CorrelationData.class));
    }

    @Test
    @DisplayName("sendAfterCommit 事务感知：活跃事务发生回滚 (不触发 afterCommit) 时零发信")
    void testSendAfterCommitRollbackZeroDispatch() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        template.sendAfterCommit("tx.exchange", "tx.key", "tx-order-payload", "biz-tx-rollback");

        // 模拟事务回滚：清空现场，不调用 afterCommit()
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);

        // 验证绝对零发信
        verify(rabbitTemplate, never()).send(any(), any(), any(), any());
    }

    @Test
    @DisplayName("sendAfterCommit 事务感知：事务物理提交后发信失败，触发 RabbitTransactionFailureCallback 补偿 SPI")
    void testSendAfterCommitFailureInvokesCallback() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        // 模拟网络抛出异常
        doThrow(new AmqpException("Broker connection reset"))
                .when(rabbitTemplate).send(eq("tx.exchange"), eq("tx.key"), any(Message.class), any(CorrelationData.class));

        AtomicBoolean callbackInvoked = new AtomicBoolean(false);
        AtomicReference<String> failedBizKey = new AtomicReference<>();
        RabbitTransactionFailureCallback failureCallback = (exchange, routingKey, payload, bizKey, cause) -> {
            callbackInvoked.set(true);
            failedBizKey.set(bizKey);
        };

        RabbitReliableTemplate txTemplate = new RabbitReliableTemplate(rabbitTemplate, properties, failureCallback);

        txTemplate.sendAfterCommit("tx.exchange", "tx.key", "order-data", "biz-fail-compensate");

        // 触发 afterCommit()
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }

        assertThat(callbackInvoked.get()).isTrue();
        assertThat(failedBizKey.get()).isEqualTo("biz-fail-compensate");
    }

    @Test
    @DisplayName("sendAfterCommit 无事务环境：直接同步可靠发信")
    void testSendAfterCommitWithoutTransaction() {
        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(eq("notx.exchange"), eq("notx.key"), any(Message.class), any(CorrelationData.class));

        template.sendAfterCommit("notx.exchange", "notx.key", "direct-payload", "biz-notx-1");

        verify(rabbitTemplate, times(1)).send(eq("notx.exchange"), eq("notx.key"), any(Message.class), any(CorrelationData.class));
    }

    @Test
    @DisplayName("getRabbitTemplate 原生逃生门：正常返回底层配置的 RabbitTemplate")
    void testGetRabbitTemplate() {
        assertThat(template.getRabbitTemplate()).isSameAs(rabbitTemplate);
    }
}
