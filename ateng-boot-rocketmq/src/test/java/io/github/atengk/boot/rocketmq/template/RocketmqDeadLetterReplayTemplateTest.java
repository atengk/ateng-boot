package io.github.atengk.boot.rocketmq.template;

import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.rocketmq.constant.RocketmqConstants;
import io.github.atengk.boot.rocketmq.helper.RocketmqMessageHelper;
import io.github.atengk.boot.rocketmq.model.DeadLetterReplayDetail;
import io.github.atengk.boot.rocketmq.model.DeadLetterReplayResult;
import io.github.atengk.boot.rocketmq.properties.AtengRocketmqProperties;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RocketmqDeadLetterReplayTemplate 单元测试
 *
 * <p>全真覆盖死信队列主题映射、只读探查 (零 ACK)、受控重放、底层重试头安全清洗、
 * 全链路审计打标、防二次雪崩熔断防线、确定性源端 ACK 以及顺序分组与解压缩处理。</p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@DisplayName("RocketmqDeadLetterReplayTemplate 死信队列治理与受控重放门面单元测试")
class RocketmqDeadLetterReplayTemplateTest {

    private AtengRocketmqProperties properties;
    private RocketmqSimpleConsumerTemplate simpleConsumerTemplate;
    private RocketmqTemplate rocketmqTemplate;
    private RocketmqDeadLetterReplayTemplate replayTemplate;

    @BeforeEach
    void setUp() {
        properties = new AtengRocketmqProperties();
        properties.getDeadLetter().setMaxReplayAttempts(3);
        properties.getDeadLetter().setDefaultBatchSize(32);
        properties.getDeadLetter().setMaxBatchSize(100);
        properties.getDeadLetter().setInvisibleDuration(Duration.ofSeconds(30));

        simpleConsumerTemplate = mock(RocketmqSimpleConsumerTemplate.class);
        rocketmqTemplate = mock(RocketmqTemplate.class);
        replayTemplate = new RocketmqDeadLetterReplayTemplate(simpleConsumerTemplate, rocketmqTemplate, properties);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        TenantContextHolder.clear();
    }

    private MessageView createMockMessageView(
            String topic,
            String msgId,
            byte[] body,
            Map<String, String> properties,
            String tag,
            String messageGroup) {
        MessageView mv = mock(MessageView.class);
        MessageId messageId = mock(MessageId.class);
        when(messageId.toString()).thenReturn(msgId);
        when(mv.getMessageId()).thenReturn(messageId);
        when(mv.getTopic()).thenReturn(topic);
        when(mv.getBody()).thenReturn(ByteBuffer.wrap(body));
        when(mv.getProperties()).thenReturn(properties != null ? properties : Collections.emptyMap());
        when(mv.getTag()).thenReturn(Optional.ofNullable(tag));
        when(mv.getKeys()).thenReturn(List.of("key-" + msgId));
        when(mv.getMessageGroup()).thenReturn(Optional.ofNullable(messageGroup));
        return mv;
    }

    private SendReceipt createMockSendReceipt(String newReceiptId) {
        SendReceipt receipt = mock(SendReceipt.class);
        MessageId messageId = mock(MessageId.class);
        when(messageId.toString()).thenReturn(newReceiptId);
        when(receipt.getMessageId()).thenReturn(messageId);
        return receipt;
    }

    // =========================================================================
    // 1. 死信主题映射测试
    // =========================================================================

    @Test
    @DisplayName("死信主题自动映射与前缀幂等处理")
    void testDlqTopicMapping() {
        assertThat(replayTemplate.getDlqTopic("GID_ORDER")).isEqualTo("%DLQ%GID_ORDER");
        assertThat(replayTemplate.getDlqTopic("%DLQ%GID_ORDER")).isEqualTo("%DLQ%GID_ORDER");
        assertThat(replayTemplate.getDlqTopic(" GID_PAY ")).isEqualTo("%DLQ%GID_PAY");

        assertThatThrownBy(() -> replayTemplate.getDlqTopic(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> replayTemplate.getDlqTopic(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> replayTemplate.getDlqTopic("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================================
    // 2. 只读探查测试 (peekDeadLetters - 零 ACK)
    // =========================================================================

    @Test
    @DisplayName("peekDeadLetters 只读探查成功拉取且绝对不执行 ACK 确认")
    void testPeekDeadLettersWithoutAck() {
        MessageView mv1 = createMockMessageView("%DLQ%GID_ORDER", "MSG_001", "hello 1".getBytes(StandardCharsets.UTF_8), null, "TAG_A", null);
        MessageView mv2 = createMockMessageView("%DLQ%GID_ORDER", "MSG_002", "hello 2".getBytes(StandardCharsets.UTF_8), null, "TAG_B", null);

        when(simpleConsumerTemplate.receive(eq("%DLQ%GID_ORDER"), eq("GID_ORDER"), eq("*"), eq(10), any(Duration.class)))
                .thenReturn(List.of(mv1, mv2));

        List<MessageView> peeked = replayTemplate.peekDeadLetters("GID_ORDER", 10);

        assertThat(peeked).hasSize(2).containsExactly(mv1, mv2);
        verify(simpleConsumerTemplate, times(1))
                .receive(eq("%DLQ%GID_ORDER"), eq("GID_ORDER"), eq("*"), eq(10), eq(Duration.ofSeconds(30)));
        // 关键断言：绝对不执行任何 ACK 确认
        verify(simpleConsumerTemplate, never()).ack(any(MessageView.class));
        verify(simpleConsumerTemplate, never()).ack(anyString(), any(MessageView.class));
    }

    @Test
    @DisplayName("peekDeadLetters 批量参数校验与 Fail-Fast")
    void testPeekDeadLettersBatchValidation() {
        assertThatThrownBy(() -> replayTemplate.peekDeadLetters("GID_ORDER", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须大于 0");

        assertThatThrownBy(() -> replayTemplate.peekDeadLetters("GID_ORDER", -5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须大于 0");

        assertThatThrownBy(() -> replayTemplate.peekDeadLetters("GID_ORDER", 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超出配置上限 [100]");

        assertThatThrownBy(() -> replayTemplate.peekDeadLetters("", 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================================
    // 3. 受控重放成功与清洗打标测试 (replayDeadLetters)
    // =========================================================================

    @Test
    @DisplayName("replayDeadLetters 成功清洗内部重试头、注入全链路审计打标并确认原死信")
    @SuppressWarnings("unchecked")
    void testReplayDeadLettersSuccessWithSanitizationAndHeaderInjection() {
        Map<String, String> dlqProps = new HashMap<>();
        dlqProps.put("%RETRY%_internal_data", "drop_me");
        dlqProps.put("__RETRY_FLAG", "1");
        dlqProps.put("__REAL_TOPIC", "order_biz_topic");
        dlqProps.put("retry_count", "16");
        dlqProps.put("custom_app_header", "ateng_app");
        dlqProps.put("X-Biz-Key", "ORDER_998877");
        dlqProps.put("X-Original-Topic", "order_biz_topic");

        byte[] payloadBytes = "order payload json content".getBytes(StandardCharsets.UTF_8);
        MessageView dlqMsg = createMockMessageView("%DLQ%GID_ORDER", "DLQ_MSG_001", payloadBytes, dlqProps, "TAG_PAY", null);

        when(simpleConsumerTemplate.receive(eq("%DLQ%GID_ORDER"), eq("GID_ORDER"), eq("*"), eq(10), any(Duration.class)))
                .thenReturn(List.of(dlqMsg));

        SendReceipt receipt = createMockSendReceipt("NEW_MSG_888");
        ArgumentCaptor<Map<String, String>> propsCaptor = ArgumentCaptor.forClass(Map.class);
        when(rocketmqTemplate.send(eq("order_biz_topic"), eq("TAG_PAY"), eq("ORDER_998877"), any(byte[].class), propsCaptor.capture()))
                .thenReturn(receipt);

        DeadLetterReplayResult result = replayTemplate.replayDeadLetters("GID_ORDER", 10);

        assertThat(result.getTotalPolled()).isEqualTo(1);
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getBlockedCount()).isEqualTo(0);
        assertThat(result.getFailedCount()).isEqualTo(0);
        assertThat(result.isAllSuccess()).isTrue();
        assertThat(result.hasFailures()).isFalse();

        // 验证清洗后的协议头与注入的审计头
        Map<String, String> sentProps = propsCaptor.getValue();
        assertThat(sentProps).doesNotContainKey("%RETRY%_internal_data");
        assertThat(sentProps).doesNotContainKey("__RETRY_FLAG");
        assertThat(sentProps).doesNotContainKey("__REAL_TOPIC");
        assertThat(sentProps).doesNotContainKey("retry_count");

        assertThat(sentProps.get("custom_app_header")).isEqualTo("ateng_app");
        assertThat(sentProps.get(RocketmqConstants.HEADER_DEAD_LETTER_REPLAYED)).isEqualTo("true");
        assertThat(sentProps.get(RocketmqConstants.HEADER_DEAD_LETTER_REPLAY_COUNT)).isEqualTo("1");
        assertThat(sentProps.get(RocketmqConstants.HEADER_DEAD_LETTER_CONSUMER_GROUP)).isEqualTo("GID_ORDER");
        assertThat(sentProps.get(RocketmqConstants.HEADER_DEAD_LETTER_ORIGINAL_TOPIC)).isEqualTo("order_biz_topic");
        assertThat(sentProps.get(RocketmqConstants.HEADER_DEAD_LETTER_ORIGINAL_MESSAGE_ID)).isEqualTo("DLQ_MSG_001");
        assertThat(sentProps).containsKey(RocketmqConstants.HEADER_DEAD_LETTER_REPLAY_TIME);

        // 关键断言：投递成功后，确认原死信
        verify(simpleConsumerTemplate, times(1)).ack(eq("GID_ORDER"), eq(dlqMsg));
    }

    @Test
    @DisplayName("replayDeadLetters 显式指定 targetTopic 时优先使用显式主题")
    void testReplayDeadLettersExplicitTargetTopic() {
        Map<String, String> dlqProps = new HashMap<>();
        dlqProps.put("X-Original-Topic", "ignored_original_topic");
        dlqProps.put("X-Biz-Key", "ORDER_EXPLICIT");

        MessageView dlqMsg = createMockMessageView("%DLQ%GID_ORDER", "DLQ_MSG_002", "hello".getBytes(StandardCharsets.UTF_8), dlqProps, "TAG_A", null);
        when(simpleConsumerTemplate.receive(eq("%DLQ%GID_ORDER"), eq("GID_ORDER"), eq("*"), eq(5), any(Duration.class)))
                .thenReturn(List.of(dlqMsg));

        SendReceipt receipt = createMockSendReceipt("NEW_MSG_002");
        when(rocketmqTemplate.send(eq("custom_dlq_recovery_topic"), eq("TAG_A"), eq("ORDER_EXPLICIT"), any(byte[].class), any()))
                .thenReturn(receipt);

        DeadLetterReplayResult result = replayTemplate.replayDeadLetters("GID_ORDER", 5, "custom_dlq_recovery_topic");

        assertThat(result.getSuccessCount()).isEqualTo(1);
        verify(rocketmqTemplate, times(1))
                .send(eq("custom_dlq_recovery_topic"), eq("TAG_A"), eq("ORDER_EXPLICIT"), any(byte[].class), any());
        verify(simpleConsumerTemplate, times(1)).ack(eq("GID_ORDER"), eq(dlqMsg));
    }

    // =========================================================================
    // 4. 防二次雪崩熔断防线测试 (Anti-Secondary Avalanche Defense)
    // =========================================================================

    @Test
    @DisplayName("replayDeadLetters 累计重放次数达到上限 3 次时强行熔断阻断重投并绝不 ACK")
    void testReplayDeadLettersAntiAvalancheBlockingOverMaxAttempts() {
        Map<String, String> dlqProps = new HashMap<>();
        dlqProps.put("X-Original-Topic", "order_topic");
        dlqProps.put("X-Biz-Key", "AVALANCHE_KEY");
        dlqProps.put(RocketmqConstants.HEADER_DEAD_LETTER_REPLAY_COUNT, "3"); // 已达到最大上限 3

        MessageView dlqMsg = createMockMessageView("%DLQ%GID_ORDER", "DLQ_AVALANCHE_MSG", "bad payload".getBytes(StandardCharsets.UTF_8), dlqProps, "TAG_A", null);
        when(simpleConsumerTemplate.receive(eq("%DLQ%GID_ORDER"), eq("GID_ORDER"), eq("*"), eq(10), any(Duration.class)))
                .thenReturn(List.of(dlqMsg));

        DeadLetterReplayResult result = replayTemplate.replayDeadLetters("GID_ORDER", 10);

        assertThat(result.getTotalPolled()).isEqualTo(1);
        assertThat(result.getSuccessCount()).isEqualTo(0);
        assertThat(result.getBlockedCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(0);
        assertThat(result.hasFailures()).isTrue();

        DeadLetterReplayDetail detail = result.getDetails().get(0);
        assertThat(detail.status()).isEqualTo(DeadLetterReplayDetail.Status.BLOCKED_BY_MAX_ATTEMPTS);
        assertThat(detail.errorMessage()).contains("防二次雪崩熔断拦截");

        // 关键断言：绝对不发起发送，绝对不执行 ACK 确认原死信
        verify(rocketmqTemplate, never()).send(anyString(), anyString(), anyString(), any(), any());
        verify(simpleConsumerTemplate, never()).ack(anyString(), any(MessageView.class));
    }

    // =========================================================================
    // 5. 确定性源端 ACK 与异常防线测试
    // =========================================================================

    @Test
    @DisplayName("replayDeadLetters 重新投递目标主题失败时绝不确认原死信")
    void testReplayDeadLettersDeterministicAckOnSendFailure() {
        Map<String, String> dlqProps = new HashMap<>();
        dlqProps.put("X-Original-Topic", "order_topic");
        dlqProps.put("X-Biz-Key", "FAIL_KEY");

        MessageView dlqMsg = createMockMessageView("%DLQ%GID_ORDER", "DLQ_FAIL_MSG", "payload".getBytes(StandardCharsets.UTF_8), dlqProps, "TAG_A", null);
        when(simpleConsumerTemplate.receive(eq("%DLQ%GID_ORDER"), eq("GID_ORDER"), eq("*"), eq(5), any(Duration.class)))
                .thenReturn(List.of(dlqMsg));

        // 模拟投递异常
        when(rocketmqTemplate.send(anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("Broker 网络超时"));

        DeadLetterReplayResult result = replayTemplate.replayDeadLetters("GID_ORDER", 5);

        assertThat(result.getTotalPolled()).isEqualTo(1);
        assertThat(result.getSuccessCount()).isEqualTo(0);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getBlockedCount()).isEqualTo(0);

        // 关键断言：投递失败时，绝对不执行原死信 ACK 确认！
        verify(simpleConsumerTemplate, never()).ack(anyString(), any(MessageView.class));
    }

    @Test
    @DisplayName("replayDeadLetters 确认原死信失败时受控记录失败并保留结果明细")
    void testReplayDeadLettersAckFailureHandledGracefully() {
        Map<String, String> dlqProps = new HashMap<>();
        dlqProps.put("X-Original-Topic", "order_topic");
        dlqProps.put("X-Biz-Key", "ACK_FAIL_KEY");

        MessageView dlqMsg = createMockMessageView("%DLQ%GID_ORDER", "DLQ_MSG_003", "payload".getBytes(StandardCharsets.UTF_8), dlqProps, "TAG_A", null);
        when(simpleConsumerTemplate.receive(eq("%DLQ%GID_ORDER"), eq("GID_ORDER"), eq("*"), eq(5), any(Duration.class)))
                .thenReturn(List.of(dlqMsg));

        SendReceipt receipt = createMockSendReceipt("NEW_RECEIPT_003");
        when(rocketmqTemplate.send(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(receipt);

        // 模拟 ACK 异常
        doThrow(new RuntimeException("SimpleConsumer 连接中断")).when(simpleConsumerTemplate).ack(eq("GID_ORDER"), eq(dlqMsg));

        DeadLetterReplayResult result = replayTemplate.replayDeadLetters("GID_ORDER", 5);

        assertThat(result.getTotalPolled()).isEqualTo(1);
        assertThat(result.getSuccessCount()).isEqualTo(0);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getDetails().get(0).status()).isEqualTo(DeadLetterReplayDetail.Status.FAILED);
        assertThat(result.getDetails().get(0).errorMessage()).contains("源端死信确认失败");
    }

    // =========================================================================
    // 6. 顺序分组保序与透明 GZIP 解压缩测试
    // =========================================================================

    @Test
    @DisplayName("replayDeadLetters 包含 messageGroup 时对称调用 sendOrderly 保序投递")
    void testReplayDeadLettersOrderlyMessageGroup() {
        Map<String, String> dlqProps = new HashMap<>();
        dlqProps.put("X-Original-Topic", "fifo_order_topic");
        dlqProps.put("X-Biz-Key", "ORDER_SHARD_1");

        MessageView dlqMsg = createMockMessageView(
                "%DLQ%GID_ORDER", "FIFO_DLQ_001", "fifo payload".getBytes(StandardCharsets.UTF_8),
                dlqProps, "TAG_FIFO", "GROUP_SHARD_101");

        when(simpleConsumerTemplate.receive(eq("%DLQ%GID_ORDER"), eq("GID_ORDER"), eq("*"), eq(5), any(Duration.class)))
                .thenReturn(List.of(dlqMsg));

        SendReceipt receipt = createMockSendReceipt("NEW_FIFO_RECEIPT");
        when(rocketmqTemplate.sendOrderly(eq("fifo_order_topic"), eq("TAG_FIFO"), eq("ORDER_SHARD_1"), any(byte[].class), eq("GROUP_SHARD_101"), any()))
                .thenReturn(receipt);

        DeadLetterReplayResult result = replayTemplate.replayDeadLetters("GID_ORDER", 5);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        verify(rocketmqTemplate, times(1))
                .sendOrderly(eq("fifo_order_topic"), eq("TAG_FIFO"), eq("ORDER_SHARD_1"), any(byte[].class), eq("GROUP_SHARD_101"), any());
        verify(simpleConsumerTemplate, times(1)).ack(eq("GID_ORDER"), eq(dlqMsg));
    }

    @Test
    @DisplayName("replayDeadLetters 包含 GZIP 压缩标头时自动透明解压报文")
    void testReplayDeadLettersCompressedGzipMessage() throws Exception {
        byte[] rawBytes = "original uncompressed big payload text content".getBytes(StandardCharsets.UTF_8);
        byte[] compressedBytes = RocketmqMessageHelper.compressGzip(rawBytes);

        Map<String, String> dlqProps = new HashMap<>();
        dlqProps.put("X-Original-Topic", "order_topic");
        dlqProps.put("X-Biz-Key", "GZIP_KEY");
        dlqProps.put(RocketmqConstants.HEADER_COMPRESSION, RocketmqConstants.COMPRESSION_GZIP);

        MessageView dlqMsg = createMockMessageView("%DLQ%GID_ORDER", "GZIP_DLQ_001", compressedBytes, dlqProps, "TAG_A", null);
        when(simpleConsumerTemplate.receive(eq("%DLQ%GID_ORDER"), eq("GID_ORDER"), eq("*"), eq(5), any(Duration.class)))
                .thenReturn(List.of(dlqMsg));

        SendReceipt receipt = createMockSendReceipt("NEW_GZIP_RECEIPT");
        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        when(rocketmqTemplate.send(eq("order_topic"), eq("TAG_A"), eq("GZIP_KEY"), bodyCaptor.capture(), any()))
                .thenReturn(receipt);

        DeadLetterReplayResult result = replayTemplate.replayDeadLetters("GID_ORDER", 5);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        // 关键断言：传递给发送端的是已透明解压的原始内容
        assertThat(bodyCaptor.getValue()).isEqualTo(rawBytes);
        verify(simpleConsumerTemplate, times(1)).ack(eq("GID_ORDER"), eq(dlqMsg));
    }

    // =========================================================================
    // 7. 边界与防御性测试
    // =========================================================================

    @Test
    @DisplayName("replayDeadLetters 无法解析原始业务主题且未指定 targetTopic 时记录未解析并跳过")
    void testReplayDeadLettersUnresolvedTopic() {
        Map<String, String> dlqProps = new HashMap<>();
        dlqProps.put("X-Biz-Key", "NO_TOPIC_KEY");

        MessageView dlqMsg = createMockMessageView("%DLQ%GID_ORDER", "NO_TOPIC_MSG", "payload".getBytes(StandardCharsets.UTF_8), dlqProps, "TAG_A", null);
        when(simpleConsumerTemplate.receive(eq("%DLQ%GID_ORDER"), eq("GID_ORDER"), eq("*"), eq(5), any(Duration.class)))
                .thenReturn(List.of(dlqMsg));

        DeadLetterReplayResult result = replayTemplate.replayDeadLetters("GID_ORDER", 5);

        assertThat(result.getTotalPolled()).isEqualTo(1);
        assertThat(result.getSuccessCount()).isEqualTo(0);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getDetails().get(0).status()).isEqualTo(DeadLetterReplayDetail.Status.UNRESOLVED_TOPIC);

        verify(rocketmqTemplate, never()).send(anyString(), anyString(), anyString(), any(), any());
        verify(simpleConsumerTemplate, never()).ack(anyString(), any(MessageView.class));
    }

    @Test
    @DisplayName("replayDeadLetters 空死信队列时保底返回空结果且不报错")
    void testReplayDeadLettersEmptyDlq() {
        when(simpleConsumerTemplate.receive(eq("%DLQ%GID_ORDER"), eq("GID_ORDER"), eq("*"), eq(32), any(Duration.class)))
                .thenReturn(Collections.emptyList());

        DeadLetterReplayResult result = replayTemplate.replayDeadLetters("GID_ORDER");

        assertThat(result.getTotalPolled()).isEqualTo(0);
        assertThat(result.getSuccessCount()).isEqualTo(0);
        assertThat(result.isAllSuccess()).isFalse();
        assertThat(result.hasFailures()).isFalse();
    }

    @Test
    @DisplayName("replayDeadLetters 批量操作数量校验拦截")
    void testReplayDeadLettersBatchValidation() {
        assertThatThrownBy(() -> replayTemplate.replayDeadLetters("GID_ORDER", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> replayTemplate.replayDeadLetters("GID_ORDER", 150))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超出配置上限 [100]");
        assertThatThrownBy(() -> replayTemplate.replayDeadLetters("GID_ORDER", 10, "invalid..topic!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("格式非法");
    }
}
