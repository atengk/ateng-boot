package io.github.atengk.boot.kafka.template;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.MessageDeliveryException;
import io.github.atengk.boot.kafka.properties.AtengKafkaProperties;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * KafkaReliableTemplate 原生分布式事务执行单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
@DisplayName("KafkaReliableTemplate 原生事务 executeInTransaction 测试")
class KafkaTransactionReliableTemplateTest {

    private KafkaTemplate<Object, Object> kafkaTemplate;
    private KafkaOperations<Object, Object> mockOperations;
    private AtengKafkaProperties properties;
    private KafkaReliableTemplate template;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        mockOperations = mock(KafkaOperations.class);
        properties = new AtengKafkaProperties();
        template = new KafkaReliableTemplate(kafkaTemplate, properties);

        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("executeInTransaction: 成功执行多主题原子事务发送并全链路注入四维上下文")
    @SuppressWarnings("unchecked")
    void testExecuteInTransactionSuccess_MultiTopicAndContextPropagation() {
        MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "trace-tx-1001");
        TenantContextHolder.setTenantId("tenant-tx-001");
        UserContextHolder.setUserId("user-tx-999");

        when(kafkaTemplate.executeInTransaction(any())).thenAnswer(invocation -> {
            KafkaOperations.OperationsCallback<Object, Object, String> callback = invocation.getArgument(0);
            return callback.doInOperations(mockOperations);
        });

        when(mockOperations.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        String result = template.executeInTransaction(operations -> {
            // 1. 发送第一个 Topic (String, Object, Object)
            operations.send("order.create.topic", "order-key-100", "{\"orderId\":\"100\"}");

            // 2. 发送第二个 Topic (ProducerRecord)
            ProducerRecord<Object, Object> auditRecord = new ProducerRecord<>("audit.log.topic", "audit-data");
            operations.send(auditRecord);

            return "TX_COMMITTED";
        });

        assertThat(result).isEqualTo("TX_COMMITTED");

        ArgumentCaptor<ProducerRecord<Object, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(mockOperations, times(2)).send(captor.capture());

        List<ProducerRecord<Object, Object>> capturedRecords = captor.getAllValues();
        assertThat(capturedRecords).hasSize(2);

        // 验证第一条消息
        ProducerRecord<Object, Object> rec1 = capturedRecords.get(0);
        assertThat(rec1.topic()).isEqualTo("order.create.topic");
        assertThat(rec1.key()).isEqualTo("order-key-100");
        assertThat(rec1.value()).isEqualTo("{\"orderId\":\"100\"}");
        assertHeader(rec1, MessageHeaderConstants.TRACE_ID, "trace-tx-1001");
        assertHeader(rec1, MessageHeaderConstants.TENANT_ID, "tenant-tx-001");
        assertHeader(rec1, MessageHeaderConstants.USER_ID, "user-tx-999");
        assertHeader(rec1, MessageHeaderConstants.BIZ_KEY, "order-key-100");

        // 验证第二条消息
        ProducerRecord<Object, Object> rec2 = capturedRecords.get(1);
        assertThat(rec2.topic()).isEqualTo("audit.log.topic");
        assertThat(rec2.value()).isEqualTo("audit-data");
        assertHeader(rec2, MessageHeaderConstants.TRACE_ID, "trace-tx-1001");
        assertHeader(rec2, MessageHeaderConstants.TENANT_ID, "tenant-tx-001");
        assertHeader(rec2, MessageHeaderConstants.USER_ID, "user-tx-999");
    }

    @Test
    @DisplayName("executeInTransaction: 底层异常转译为 MessageDeliveryException 并保留原始因果链")
    @SuppressWarnings("unchecked")
    void testExecuteInTransaction_ExceptionTranslation() {
        when(kafkaTemplate.executeInTransaction(any())).thenAnswer(invocation -> {
            throw new RuntimeException("Kafka 集群事务协调器不可用");
        });

        assertThatThrownBy(() -> template.executeInTransaction(operations -> {
            operations.send("topic.test", "data");
            return "OK";
        }))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Kafka 原生分布式事务执行失败")
                .hasMessageContaining("Kafka 集群事务协调器不可用")
                .satisfies(ex -> {
                    MessageDeliveryException mde = (MessageDeliveryException) ex;
                    assertThat(mde.getMqType()).isEqualTo("kafka");
                    assertThat(mde.getResultCode()).isEqualTo(CommonResultCode.INTERNAL_SERVER_ERROR);
                    assertThat(mde.getCause()).isNotNull();
                    assertThat(mde.getCause().getMessage()).contains("Kafka 集群事务协调器不可用");
                });
    }

    @Test
    @DisplayName("executeInTransaction: 业务已显式抛出 MessageDeliveryException 时直接原样向外透传")
    @SuppressWarnings("unchecked")
    void testExecuteInTransaction_MessageDeliveryExceptionDirectRethrow() {
        MessageDeliveryException businessException = new MessageDeliveryException(
                "kafka",
                "order.topic",
                "biz-101",
                CommonResultCode.BAD_REQUEST,
                "业务校验失败，回滚 Kafka 事务",
                null
        );

        when(kafkaTemplate.executeInTransaction(any())).thenAnswer(invocation -> {
            throw businessException;
        });

        assertThatThrownBy(() -> template.executeInTransaction(operations -> "OK"))
                .isSameAs(businessException);
    }

    @Test
    @DisplayName("executeInTransaction: callback 参数非空校验快速失败")
    void testExecuteInTransaction_NullCallbackValidation() {
        assertThatThrownBy(() -> template.executeInTransaction(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("callback 不能为空");
    }

    @Test
    @DisplayName("executeInTransaction: 单条消息体积超过 maxPayloadSize 时在代理层 Fail-Fast 阻断，严禁静默透传")
    @SuppressWarnings("unchecked")
    void testExecuteInTransaction_OversizedPayloadFailFast() {
        properties.getProducer().setMaxPayloadSize(DataSize.ofKilobytes(5));

        when(kafkaTemplate.executeInTransaction(any())).thenAnswer(invocation -> {
            KafkaOperations.OperationsCallback<Object, Object, String> callback = invocation.getArgument(0);
            return callback.doInOperations(mockOperations);
        });

        byte[] oversized = new byte[6 * 1024];

        // 1. 通过 send(topic, key, payload) 重载发送超限载荷
        assertThatThrownBy(() -> template.executeInTransaction(operations -> {
            operations.send("order.tx.topic", "tx-biz-1", oversized);
            return "SHOULD_NOT_COMMIT";
        }))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("出站消息报文体积超出最大限制")
                .satisfies(ex -> {
                    MessageDeliveryException mde = (MessageDeliveryException) ex;
                    assertThat(mde.getMqType()).isEqualTo("kafka");
                    assertThat(mde.getTopic()).isEqualTo("order.tx.topic");
                    assertThat(mde.getBizKey()).isEqualTo("tx-biz-1");
                    assertThat(mde.getResultCode()).isEqualTo(CommonResultCode.BAD_REQUEST);
                });

        // 2. 通过 send(ProducerRecord) 重载发送超限载荷
        ProducerRecord<Object, Object> oversizedRecord = new ProducerRecord<>("audit.tx.topic", "tx-biz-2", oversized);
        assertThatThrownBy(() -> template.executeInTransaction(operations -> {
            operations.send(oversizedRecord);
            return "SHOULD_NOT_COMMIT";
        }))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("出站消息报文体积超出最大限制")
                .satisfies(ex -> {
                    MessageDeliveryException mde = (MessageDeliveryException) ex;
                    assertThat(mde.getTopic()).isEqualTo("audit.tx.topic");
                    assertThat(mde.getBizKey()).isEqualTo("tx-biz-2");
                    assertThat(mde.getResultCode()).isEqualTo(CommonResultCode.BAD_REQUEST);
                });

        // 校验底层 mockOperations 从未被调用，实现前置 Fail-Fast 阻断
        verify(mockOperations, never()).send(any(ProducerRecord.class));
        verify(mockOperations, never()).send(anyString(), any(), any());
    }

    @Test
    @DisplayName("executeInTransaction: send(Message) 与 sendDefault 防旁路校验（上下文透传、精确边界与超限阻断）")
    @SuppressWarnings("unchecked")
    void testExecuteInTransaction_MessageAndSendDefaultProtectionAndExactBoundary() {
        properties.getProducer().setMaxPayloadSize(DataSize.ofBytes(1024));
        MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "trace-bypass-guard");
        TenantContextHolder.setTenantId("tenant-bypass");
        UserContextHolder.setUserId("user-bypass");

        when(kafkaTemplate.getDefaultTopic()).thenReturn("default.tx.topic");
        when(kafkaTemplate.executeInTransaction(any())).thenAnswer(invocation -> {
            KafkaOperations.OperationsCallback<Object, Object, String> callback = invocation.getArgument(0);
            return callback.doInOperations(mockOperations);
        });
        when(mockOperations.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        when(mockOperations.send(any(org.springframework.messaging.Message.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        // 1. 精确边界 (payload.length == maxPayloadSize = 1024) 允许通过；send(topic, payload) 与 send(topic, partition, key, payload) 正常透传
        byte[] exactLimit = new byte[1024];
        org.springframework.messaging.Message<byte[]> validSpringMsg = org.springframework.messaging.support.MessageBuilder
                .withPayload(exactLimit)
                .setHeader(org.springframework.kafka.support.KafkaHeaders.TOPIC, "msg.tx.topic")
                .setHeader(org.springframework.kafka.support.KafkaHeaders.KEY, "msg-key-1")
                .build();

        String res = template.executeInTransaction(operations -> {
            operations.send("simple.tx.topic", exactLimit);
            operations.send("part.tx.topic", 2, "part-key", exactLimit);
            operations.send(validSpringMsg);
            operations.sendDefault("def-key", exactLimit);
            return "BOUNDARY_OK";
        });
        assertThat(res).isEqualTo("BOUNDARY_OK");

        ArgumentCaptor<org.springframework.messaging.Message<?>> msgCaptor =
                ArgumentCaptor.forClass(org.springframework.messaging.Message.class);
        verify(mockOperations, times(1)).send(msgCaptor.capture());
        assertThat(msgCaptor.getValue().getHeaders().get(MessageHeaderConstants.TRACE_ID))
                .isEqualTo("trace-bypass-guard");
        assertThat(msgCaptor.getValue().getHeaders().get(MessageHeaderConstants.TENANT_ID))
                .isEqualTo("tenant-bypass");
        assertThat(msgCaptor.getValue().getHeaders().get(MessageHeaderConstants.BIZ_KEY))
                .isEqualTo("msg-key-1");

        // 校验 send(topic, payload), send(topic, partition, key, payload) 以及 sendDefault 都转为带 4D 头的 ProducerRecord
        ArgumentCaptor<ProducerRecord<Object, Object>> recordCaptor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(mockOperations, times(3)).send(recordCaptor.capture());
        assertThat(recordCaptor.getAllValues().get(2).topic()).isEqualTo("default.tx.topic");
        assertHeader(recordCaptor.getAllValues().get(2), MessageHeaderConstants.TRACE_ID, "trace-bypass-guard");

        // 2. 紧超界 (maxPayloadSize + 1 = 1025) 通过 send(Message) 与 sendDefault 均必须被 Fail-Fast 阻断
        byte[] overByOne = new byte[1025];
        org.springframework.messaging.Message<byte[]> oversizedMsg = org.springframework.messaging.support.MessageBuilder
                .withPayload(overByOne)
                .setHeader(org.springframework.kafka.support.KafkaHeaders.TOPIC, "msg.tx.topic")
                .build();

        assertThatThrownBy(() -> template.executeInTransaction(operations -> {
            operations.send(oversizedMsg);
            return "FAIL";
        }))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("出站消息报文体积超出最大限制");

        assertThatThrownBy(() -> template.executeInTransaction(operations -> {
            operations.sendDefault("def-over-key", overByOne);
            return "FAIL";
        }))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("出站消息报文体积超出最大限制");

        // 未配置 defaultTopic 时，sendDefault 仍必须触发 Fail-Fast 阻断
        when(kafkaTemplate.getDefaultTopic()).thenReturn(null);
        assertThatThrownBy(() -> template.executeInTransaction(operations -> {
            operations.sendDefault(overByOne);
            return "FAIL";
        }))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("出站消息报文体积超出最大限制");

        verify(mockOperations, times(1)).send(any(org.springframework.messaging.Message.class));
        verify(mockOperations, times(3)).send(any(ProducerRecord.class));
        verify(mockOperations, never()).sendDefault(any());
        verify(mockOperations, never()).sendDefault(any(), any());
    }

    private void assertHeader(ProducerRecord<Object, Object> record, String key, String expectedValue) {
        Header header = record.headers().lastHeader(key);
        assertThat(header).as("Header %s should not be null", key).isNotNull();
        String actualValue = new String(header.value(), StandardCharsets.UTF_8);
        assertThat(actualValue).isEqualTo(expectedValue);
    }
}
