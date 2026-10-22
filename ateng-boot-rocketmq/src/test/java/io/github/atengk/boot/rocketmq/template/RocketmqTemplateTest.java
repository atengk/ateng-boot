package io.github.atengk.boot.rocketmq.template;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.MessageDeliveryException;
import io.github.atengk.boot.rocketmq.constant.RocketmqConstants;
import io.github.atengk.boot.rocketmq.helper.RocketmqMessageHelper;
import io.github.atengk.boot.rocketmq.properties.AtengRocketmqProperties;
import io.github.atengk.boot.rocketmq.spi.RocketmqTransactionFailureCallback;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * RocketmqTemplate 生产门面单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("RocketmqTemplate 生产门面单元测试")
class RocketmqTemplateTest {

    private Producer producer;
    private AtengRocketmqProperties properties;
    private RocketmqTemplate rocketmqTemplate;
    private SendReceipt mockReceipt;
    private MessageId mockMessageId;

    @BeforeEach
    void setUp() throws Exception {
        producer = mock(Producer.class);
        properties = new AtengRocketmqProperties();
        rocketmqTemplate = new RocketmqTemplate(producer, properties);

        mockReceipt = mock(SendReceipt.class);
        mockMessageId = mock(MessageId.class);
        when(mockMessageId.toString()).thenReturn("MSG-001");
        when(mockReceipt.getMessageId()).thenReturn(mockMessageId);

        when(producer.send(any(Message.class))).thenReturn(mockReceipt);
        when(producer.sendAsync(any(Message.class))).thenReturn(CompletableFuture.completedFuture(mockReceipt));

        MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "trace-test-888");
        TenantContextHolder.setTenantId("tenant-999");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        TenantContextHolder.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("验证同步发送：四维上下文、Tag、Keys、Payload 正确封装并返回 Receipt")
    void testSendSync() throws Exception {
        Map<String, String> payload = Collections.singletonMap("orderNo", "ORD-12345");
        SendReceipt receipt = rocketmqTemplate.send("order_topic", "create", "BIZ-ORD-12345", payload);

        assertThat(receipt).isSameAs(mockReceipt);
        assertThat(rocketmqTemplate.getProducer()).isSameAs(producer);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(captor.capture());

        Message captured = captor.getValue();
        assertThat(captured.getTopic()).isEqualTo("order_topic");
        assertThat(captured.getTag()).contains("create");
        assertThat(captured.getKeys()).contains("BIZ-ORD-12345");
        assertThat(captured.getProperties()).containsEntry(MessageHeaderConstants.TRACE_ID, "trace-test-888");
        assertThat(captured.getProperties()).containsEntry(MessageHeaderConstants.TENANT_ID, "tenant-999");
        assertThat(captured.getProperties()).containsEntry(MessageHeaderConstants.BIZ_KEY, "BIZ-ORD-12345");

        byte[] body = new byte[captured.getBody().remaining()];
        captured.getBody().get(body);
        assertThat(new String(body, StandardCharsets.UTF_8)).contains("ORD-12345");
    }

    @Test
    @DisplayName("验证异步非阻塞发送：sendAsync 返回正常 CompletableFuture")
    void testSendAsync() {
        CompletableFuture<SendReceipt> future = rocketmqTemplate.sendAsync("notice_topic", "hello");
        assertThat(future).isCompletedWithValue(mockReceipt);
    }

    @Test
    @DisplayName("验证延时发送：sendDelayed 正确换算毫秒级 deliveryTimestamp")
    void testSendDelayed() throws Exception {
        long beforeTime = System.currentTimeMillis();
        rocketmqTemplate.sendDelayed("delay_topic", "tagD", "delay-payload", Duration.ofMinutes(10));

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(captor.capture());

        Message captured = captor.getValue();
        assertThat(captured.getDeliveryTimestamp()).isPresent();
        long timestamp = captured.getDeliveryTimestamp().get();
        assertThat(timestamp).isGreaterThanOrEqualTo(beforeTime + 600000L);
    }

    @Test
    @DisplayName("验证定时发送：sendDeliverAt 精准指定 Instant 绝对时间戳")
    void testSendDeliverAt() throws Exception {
        Instant deliverAt = Instant.ofEpochMilli(1800000000000L);
        rocketmqTemplate.sendDeliverAt("schedule_topic", "tagS", "schedule-payload", deliverAt);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(captor.capture());

        Message captured = captor.getValue();
        assertThat(captured.getDeliveryTimestamp()).contains(1800000000000L);
    }

    @Test
    @DisplayName("验证 FIFO 顺序消息：sendOrderly 正确设置 messageGroup 属性")
    void testSendOrderly() throws Exception {
        rocketmqTemplate.sendOrderly("order_fifo_topic", "tagO", "order-100", "GROUP-ORD-100");

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(captor.capture());

        Message captured = captor.getValue();
        assertThat(captured.getMessageGroup()).contains("GROUP-ORD-100");
    }

    @Test
    @DisplayName("验证大报文 Fail-Fast：超过配置上限立即抛出 MessageDeliveryException")
    void testMaxPayloadSizeFailFast() {
        properties.getProducer().setMaxPayloadSize(DataSize.ofBytes(15));

        assertThatThrownBy(() -> rocketmqTemplate.send("large_topic", "this is a very large payload exceeding 15 bytes"))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("超过配置上限");
    }

    @Test
    @DisplayName("验证透明 GZIP 压缩：超过阈值自动压缩并在 Properties 标记 X-Compression")
    void testTransparentGzipCompression() throws Exception {
        properties.getProducer().setCompressionThreshold(DataSize.ofBytes(20));

        String largeText = "RocketMQ GZIP Transparent Compression Test! ".repeat(10);
        rocketmqTemplate.send("gzip_topic", largeText);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(captor.capture());

        Message captured = captor.getValue();
        assertThat(captured.getProperties()).containsEntry(RocketmqConstants.HEADER_COMPRESSION, RocketmqConstants.COMPRESSION_GZIP);

        byte[] compressedBytes = new byte[captured.getBody().remaining()];
        captured.getBody().get(compressedBytes);

        byte[] decompressed = RocketmqMessageHelper.decompressGzip(compressedBytes);
        assertThat(new String(decompressed, StandardCharsets.UTF_8)).contains("RocketMQ GZIP Transparent");
    }

    @Test
    @DisplayName("验证异常转译：底层 ClientException 被转译为 MessageDeliveryException 并保留 cause")
    void testExceptionTranslation() throws Exception {
        when(producer.send(any(Message.class))).thenThrow(new ClientException("Broker connection timeout"));

        assertThatThrownBy(() -> rocketmqTemplate.send("fail_topic", "payload"))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Broker connection timeout")
                .hasCauseInstanceOf(ClientException.class);
    }

    @Test
    @DisplayName("验证 sendAfterCommit：无活动事务时立即物理发送")
    void testSendAfterCommitWithoutTransaction() throws Exception {
        rocketmqTemplate.sendAfterCommit("tx_topic", "hello-no-tx");
        verify(producer).send(any(Message.class));
    }

    @Test
    @DisplayName("验证 sendAfterCommit：存在活动事务时在 afterCommit 钩子中触发，失败回调 SPI")
    void testSendAfterCommitWithTransactionAndFailureCallback() throws Exception {
        AtomicBoolean callbackInvoked = new AtomicBoolean(false);
        RocketmqTransactionFailureCallback failureCallback = (topic, bizKey, payload, cause) -> {
            callbackInvoked.set(true);
            assertThat(topic).isEqualTo("tx_active_topic");
            assertThat(bizKey).isEqualTo("TX-BIZ-1");
        };

        RocketmqTemplate txTemplate = new RocketmqTemplate(producer, properties, failureCallback);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            // 注册事务同步后发信
            txTemplate.sendAfterCommit("tx_active_topic", "tagTx", "TX-BIZ-1", "tx-payload", null);

            // 尚未触发 afterCommit 时不应该物理发信
            verify(producer, never()).send(any(Message.class));

            // 模拟发送异常
            when(producer.send(any(Message.class))).thenThrow(new RuntimeException("Simulated network down"));

            // 触发所有 afterCommit 钩子
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }

            // 验证触发了物理发送且捕获异常后回调了 SPI
            verify(producer).send(any(Message.class));
            assertThat(callbackInvoked).isTrue();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
