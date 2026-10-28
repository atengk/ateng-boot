package io.github.atengk.boot.sample.kafka;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.kafka.constant.KafkaConstants;
import org.springframework.kafka.support.converter.ByteArrayJsonMessageConverter;
import io.github.atengk.boot.kafka.listener.AtengDeadLetterPublishingRecoverer;
import io.github.atengk.boot.kafka.listener.AtengKafkaErrorHandler;
import io.github.atengk.boot.kafka.template.KafkaReliableTemplate;
import io.github.atengk.boot.sample.kafka.config.KafkaScenarioTopologyConfig;
import io.github.atengk.boot.sample.kafka.consumer.*;
import io.github.atengk.boot.sample.kafka.model.OrderMessage;
import io.github.atengk.boot.sample.kafka.model.UserEventMessage;
import io.github.atengk.boot.sample.kafka.producer.TradeKafkaProducer;
import io.github.atengk.boot.sample.kafka.repository.MockOrderRepository;
import io.github.atengk.boot.sample.kafka.service.KafkaOrderTransactionService;
import jakarta.validation.ValidationException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ResolvableType;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Kafka 场景沙箱 1:1 端到端离线集成测试套件
 *
 * <p>全面覆盖 8 大端到端场景：
 * <ul>
 *   <li>场景 1：Key 分区保序与可靠收发 (sync, async, partition-directed)；</li>
 *   <li>场景 2：本地事务提交感知发信与回滚防幽灵消息 (sendAfterCommit + SPI 补偿)；</li>
 *   <li>场景 3：微服务推断反序列化解耦与泛型集合接收；</li>
 *   <li>场景 4：@Idempotent 消费端幂等拦截与静默提交 Offset；</li>
 *   <li>场景 5：毒丸非法报文 0 次重试快速进 *.DLT 与死信终点不变量；</li>
 *   <li>场景 6：BATCH_CONTAINER_FACTORY 批量拉取与 KafkaRecordExecutor 多租户安全隔离；</li>
 *   <li>场景 7：1MB 报文超限 Fail-Fast 拦截；</li>
 *   <li>场景 8：[MQ-SEND] / [MQ-CONSUME] 结构化宏日志与可观测性保障。</li>
 * </ul>
 * 100% 离线自洽，零外部物理 Broker 依赖，秒级全绿。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@SpringBootTest(classes = {SampleKafkaApplication.class, SampleKafkaE2eTest.TestKafkaConfig.class})
@DisplayName("Kafka 场景沙箱 1:1 端到端离线集成测试")
class SampleKafkaE2eTest {

    @Autowired
    private TradeKafkaProducer producer;

    @Autowired
    private KafkaOrderTransactionService transactionService;

    @Autowired
    private OrderSingleRecordConsumer orderSingleRecordConsumer;

    @Autowired
    private BatchRecordConsumer batchRecordConsumer;

    @Autowired
    private IdempotentKafkaConsumer idempotentConsumer;

    @Autowired
    private PoisonPillKafkaConsumer poisonPillConsumer;

    @Autowired
    private DeadLetterAuditConsumer deadLetterAuditConsumer;

    @Autowired
    private KafkaScenarioTopologyConfig.SampleTxFailureCallback sampleTxFailureCallback;

    @Autowired
    private AtengKafkaErrorHandler errorHandler;

    @Autowired
    private AtengDeadLetterPublishingRecoverer recoverer;

    @Autowired
    private KafkaReliableTemplate kafkaReliableTemplate;

    @Autowired
    private MockOrderRepository mockOrderRepository;

    @Autowired
    @Qualifier(KafkaConstants.DEFAULT_CONTAINER_FACTORY)
    private ConcurrentKafkaListenerContainerFactory<?, ?> defaultKafkaListenerContainerFactory;

    @Autowired
    @Qualifier(KafkaConstants.BATCH_CONTAINER_FACTORY)
    private ConcurrentKafkaListenerContainerFactory<?, ?> batchKafkaListenerContainerFactory;

    @BeforeEach
    void setUp() {
        orderSingleRecordConsumer.clear();
        batchRecordConsumer.clear();
        idempotentConsumer.clear();
        deadLetterAuditConsumer.clear();
        transactionService.clear();
        mockOrderRepository.clear();
        sampleTxFailureCallback.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("场景 1: Key 分区保序与同步、异步、物理分区可靠收发")
    void testScenario1_KeyPartitionAndReliableSend() {
        UserContextHolder.setUserId("user-001");
        TenantContextHolder.setTenantId("tenant-001");
        MDC.put("traceId", "trace-sc1");

        // 1. 同步按 Key 发送订单
        OrderMessage order = new OrderMessage("ORD-1001", new BigDecimal("299.00"), "user-001", "tenant-001");
        SendResult<Object, Object> syncResult = producer.sendSyncOrder(order);
        assertThat(syncResult).isNotNull();
        assertThat(syncResult.getRecordMetadata().topic()).isEqualTo(KafkaScenarioTopologyConfig.TOPIC_ORDER);

        // 2. 异步发送用户事件
        UserEventMessage event = new UserEventMessage("user-001", "LOGIN", System.currentTimeMillis());
        CompletableFuture<SendResult<Object, Object>> asyncFuture = producer.sendAsyncUserEvent(event);
        assertThat(asyncFuture).isCompleted();
        SendResult<Object, Object> asyncResult = asyncFuture.join();
        assertThat(asyncResult.getRecordMetadata().topic()).isEqualTo(KafkaScenarioTopologyConfig.TOPIC_USER);

        // 3. 显式指定物理分区 2 投递
        SendResult<Object, Object> partitionResult = producer.sendPartitionDirected(2, "p-key", order);
        assertThat(partitionResult.getRecordMetadata().partition()).isEqualTo(2);

        // 4. 验证单条监听器成功消费
        assertThat(orderSingleRecordConsumer.getConsumedRecords()).isNotEmpty();
        OrderSingleRecordConsumer.ConsumedRecord consumed = orderSingleRecordConsumer.getConsumedRecords().get(0);
        assertThat(consumed.order().getOrderId()).isEqualTo("ORD-1001");
        assertThat(consumed.key()).isEqualTo("ORD-1001");
        assertThat(consumed.traceId()).isEqualTo("trace-sc1");
        assertThat(consumed.tenantId()).isEqualTo("tenant-001");
        assertThat(consumed.userId()).isEqualTo("user-001");
    }

    @Test
    @DisplayName("场景 2: 本地事务提交感知发信与回滚防幽灵消息 (sendAfterCommit + 补偿 SPI)")
    void testScenario2_TransactionalSendAfterCommitAndRollback() {
        OrderMessage successOrder = new OrderMessage("ORD-TX-OK", new BigDecimal("500.00"), "user-tx", "tenant-tx");

        // 1. 事务成功提交 -> 触发 afterCommit 物理发信
        transactionService.createOrderWithSendAfterCommit(successOrder);
        assertThat(transactionService.getLocalDbOrders()).contains("ORD-TX-OK");
        assertThat(orderSingleRecordConsumer.getConsumedRecords()).anyMatch(r -> "ORD-TX-OK".equals(r.order().getOrderId()));

        orderSingleRecordConsumer.clear();

        // 2. 事务异常回滚 -> 物理发信被阻断，严禁产生幽灵消息
        OrderMessage rollbackOrder = new OrderMessage("ORD-TX-FAIL", new BigDecimal("999.00"), "user-tx", "tenant-tx");
        assertThatThrownBy(() -> transactionService.createOrderWithRollback(rollbackOrder))
                .isInstanceOf(IllegalStateException.class);

        // 确认没有消费记录产生
        assertThat(orderSingleRecordConsumer.getConsumedRecords()).noneMatch(r -> "ORD-TX-FAIL".equals(r.order().getOrderId()));
    }

    @Test
    @DisplayName("场景 3: 微服务推断反序列化解耦与泛型集合接收 (无需 __TypeId__ 强耦合)")
    void testScenario3_InferredDeserializationWithoutTypeId() {
        ByteArrayJsonMessageConverter converter = new ByteArrayJsonMessageConverter();
        String json = "{\"orderId\":\"ORD-INFER-01\",\"amount\":123.45,\"userId\":\"user-inf\",\"tenantId\":\"tenant-inf\"}";

        RecordHeaders headers = new RecordHeaders();
        headers.add(new RecordHeader(MessageHeaderConstants.TRACE_ID, "trace-inf".getBytes(StandardCharsets.UTF_8)));

        ConsumerRecord<byte[], byte[]> rawRecord = new ConsumerRecord<>(
                KafkaScenarioTopologyConfig.TOPIC_ORDER, 0, 10L,
                "ORD-INFER-01".getBytes(StandardCharsets.UTF_8),
                json.getBytes(StandardCharsets.UTF_8)
        );

        // 1. 单对象推断反序列化
        Message<?> springMessage = converter.toMessage(rawRecord, null, null, OrderMessage.class);
        assertThat(springMessage.getPayload()).isInstanceOf(OrderMessage.class);

        OrderMessage convertedOrder = (OrderMessage) springMessage.getPayload();
        assertThat(convertedOrder.getOrderId()).isEqualTo("ORD-INFER-01");
        assertThat(convertedOrder.getAmount()).isEqualByComparingTo(new BigDecimal("123.45"));

        // 2. 泛型集合推断反序列化（基于 ResolvableType，杜绝退化为 List<LinkedHashMap>）
        String listJson = "[{\"orderId\":\"ORD-LIST-01\",\"amount\":100.00,\"userId\":\"u1\",\"tenantId\":\"t1\"},"
                + "{\"orderId\":\"ORD-LIST-02\",\"amount\":200.00,\"userId\":\"u2\",\"tenantId\":\"t2\"}]";
        ConsumerRecord<byte[], byte[]> listRecord = new ConsumerRecord<>(
                KafkaScenarioTopologyConfig.TOPIC_ORDER, 0, 11L,
                "list-key".getBytes(StandardCharsets.UTF_8),
                listJson.getBytes(StandardCharsets.UTF_8)
        );
        java.lang.reflect.Type listOfOrdersType = ResolvableType
                .forClassWithGenerics(List.class, OrderMessage.class).getType();
        Message<?> listMessage = converter.toMessage(listRecord, null, null, listOfOrdersType);
        assertThat(listMessage.getPayload()).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<OrderMessage> orderList = (List<OrderMessage>) listMessage.getPayload();
        assertThat(orderList).hasSize(2);
        assertThat(orderList.get(0)).isInstanceOf(OrderMessage.class);
        assertThat(orderList.get(0).getOrderId()).isEqualTo("ORD-LIST-01");
        assertThat(orderList.get(1)).isInstanceOf(OrderMessage.class);
        assertThat(orderList.get(1).getOrderId()).isEqualTo("ORD-LIST-02");
    }

    @Test
    @DisplayName("场景 4: @Idempotent 消费端幂等拦截与静默提交 Offset")
    void testScenario4_IdempotentDuplicateSilentCommit() {
        OrderMessage order = new OrderMessage("ORD-IDEMP-001", new BigDecimal("88.00"), "u-1", "t-1");

        // 1. 首次消费：正常调用监听方法，消费成功
        idempotentConsumer.onOrderMessage(order);
        assertThat(idempotentConsumer.getProcessedOrderIds()).contains("ORD-IDEMP-001");
        assertThat(idempotentConsumer.getIdempotentHitCount().get()).isEqualTo(0);

        // 2. 第二次消费：触发 IdempotentException 幂等拦截
        assertThatThrownBy(() -> idempotentConsumer.onOrderMessage(order))
                .isInstanceOf(IdempotentException.class);
        assertThat(idempotentConsumer.getIdempotentHitCount().get()).isEqualTo(1);

        // 3. 验证 Recoverer 对 IdempotentException 静默提交且不进入 DLT
        ConsumerRecord<Object, Object> record = new ConsumerRecord<>(
                KafkaScenarioTopologyConfig.TOPIC_ORDER, 0, 50L, "ORD-IDEMP-001", order
        );
        TopicPartition destination = recoverer.resolveDestination(record, new IdempotentException("重复订单"));
        assertThat(destination).isNull(); // 返回 null 触发 DefaultErrorHandler 静默提交 Offset
    }

    @Test
    @DisplayName("场景 5: 毒丸非法报文 0 次重试快速进 *.DLT 与死信终点不变量")
    void testScenario5_PoisonPillZeroRetryToDltAndTerminalInvariant() {
        // 1. 验证毒丸异常属于 FatalExceptionClassifier 判定范围
        ValidationException poisonException = new ValidationException("报文格式非法");
        ConsumerRecord<Object, Object> poisonRecord = new ConsumerRecord<>(
                KafkaScenarioTopologyConfig.TOPIC_POISON, 0, 99L, "poison-k", "CORRUPTED_POISON"
        );

        // 2. 错误处理器配置 0 次重试
        BackOff backOff = errorHandler.getCustomBackOffFunction().apply(poisonRecord, poisonException);
        assertThat(backOff).isInstanceOf(FixedBackOff.class);
        FixedBackOff fixedBackOff = (FixedBackOff) backOff;
        assertThat(fixedBackOff.getMaxAttempts()).isEqualTo(0L);

        // 3. 验证正常失败转移至配套 .DLT
        TopicPartition normalDlt = recoverer.resolveDestination(poisonRecord, poisonException);
        assertThat(normalDlt).isNotNull();
        assertThat(normalDlt.topic()).isEqualTo(KafkaScenarioTopologyConfig.TOPIC_POISON + ".DLT");

        // 4. 验证死信终点不变量 (Terminal Invariant)：DLT 再次失败绝对禁止循环反弹
        ConsumerRecord<Object, Object> dltRecord = new ConsumerRecord<>(
                KafkaScenarioTopologyConfig.TOPIC_POISON + ".DLT", 0, 100L, "poison-k", "CORRUPTED_POISON"
        );
        TopicPartition terminalDlt = recoverer.resolveDestination(dltRecord, new RuntimeException("DLT 消费异常"));
        assertThat(terminalDlt).isNull(); // 阻断死信循环反弹

        // 5. 验证死信归档审计监听器 (DeadLetterAuditConsumer) 消费与字段提取
        deadLetterAuditConsumer.onDeadLetter(
                poisonRecord,
                normalDlt.topic(),
                "报文格式非法，触发 JSR-303 致命校验异常".getBytes(StandardCharsets.UTF_8)
        );
        assertThat(deadLetterAuditConsumer.getDeadLetters()).hasSize(1);
        DeadLetterAuditConsumer.DeadLetterEntry entry = deadLetterAuditConsumer.getDeadLetters().get(0);
        assertThat(entry.topic()).isEqualTo(KafkaScenarioTopologyConfig.TOPIC_POISON + ".DLT");
        assertThat(entry.key()).isEqualTo("poison-k");
        assertThat(entry.payload()).isEqualTo("CORRUPTED_POISON");
        assertThat(entry.exceptionMessage()).contains("报文格式非法");
    }

    @Test
    @DisplayName("场景 6: BATCH_CONTAINER_FACTORY 批量拉取与 KafkaRecordExecutor 多租户安全隔离")
    void testScenario6_BatchMultiTenantContextIsolation() {
        List<ConsumerRecord<String, OrderMessage>> batchRecords = new ArrayList<>();

        // 构建 3 条不同租户的消息
        OrderMessage orderA = new OrderMessage("ORD-A", new BigDecimal("10"), "uA", "tenant-A");
        OrderMessage orderB = new OrderMessage("ORD-B", new BigDecimal("20"), "uB", "tenant-B");
        OrderMessage orderC = new OrderMessage("ORD-C", new BigDecimal("30"), "uC", "tenant-C");

        RecordHeaders hA = new RecordHeaders();
        hA.add(new RecordHeader(MessageHeaderConstants.TENANT_ID, "tenant-A".getBytes(StandardCharsets.UTF_8)));
        ConsumerRecord<String, OrderMessage> recA = new ConsumerRecord<>(KafkaScenarioTopologyConfig.TOPIC_BATCH, 0, 1L, 0L, null, 0, 0, "ORD-A", orderA, hA, Optional.empty());

        RecordHeaders hB = new RecordHeaders();
        hB.add(new RecordHeader(MessageHeaderConstants.TENANT_ID, "tenant-B".getBytes(StandardCharsets.UTF_8)));
        ConsumerRecord<String, OrderMessage> recB = new ConsumerRecord<>(KafkaScenarioTopologyConfig.TOPIC_BATCH, 1, 2L, 0L, null, 0, 0, "ORD-B", orderB, hB, Optional.empty());

        RecordHeaders hC = new RecordHeaders();
        hC.add(new RecordHeader(MessageHeaderConstants.TENANT_ID, "tenant-C".getBytes(StandardCharsets.UTF_8)));
        ConsumerRecord<String, OrderMessage> recC = new ConsumerRecord<>(KafkaScenarioTopologyConfig.TOPIC_BATCH, 2, 3L, 0L, null, 0, 0, "ORD-C", orderC, hC, Optional.empty());

        batchRecords.add(recA);
        batchRecords.add(recB);
        batchRecords.add(recC);

        // 调用批量消费监听器
        batchRecordConsumer.onBatchRecords(batchRecords);

        // 验证每条记录执行时捕获的 TenantContextHolder 与消息体完全隔离对齐
        assertThat(batchRecordConsumer.getProcessedItems()).hasSize(3);
        assertThat(batchRecordConsumer.getProcessedItems().get(0).tenantId()).isEqualTo("tenant-A");
        assertThat(batchRecordConsumer.getProcessedItems().get(1).tenantId()).isEqualTo("tenant-B");
        assertThat(batchRecordConsumer.getProcessedItems().get(2).tenantId()).isEqualTo("tenant-C");

        // 验证批次消费完毕后 ThreadLocal 现场绝对干净
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    @Test
    @DisplayName("场景 7: 1MB 报文超限 Fail-Fast 拦截")
    void testScenario7_PayloadTooLargeFailFast() {
        // 创建超过 1MB (1048576 字节) 的超大报文
        byte[] largePayload = new byte[1048576 + 64];

        assertThatThrownBy(() -> producer.sendLargePayload(largePayload))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getResultCode()).isEqualTo(CommonResultCode.BAD_REQUEST);
                    assertThat(be.getMessage()).contains("超出最大限制");
                });
    }

    @Test
    @DisplayName("场景 8: [MQ-SEND] / [MQ-CONSUME] 结构化宏日志与可观测性保障")
    void testScenario8_StructuredMacroLoggingAndMetadata() {
        UserContextHolder.setUserId("audit-user");
        TenantContextHolder.setTenantId("audit-tenant");
        MDC.put("traceId", "audit-trace-888");

        OrderMessage order = new OrderMessage("ORD-AUDIT", new BigDecimal("66.66"), "audit-user", "audit-tenant");
        SendResult<Object, Object> sendResult = producer.sendSyncOrder(order);

        assertThat(sendResult).isNotNull();
        assertThat(sendResult.getRecordMetadata().topic()).isEqualTo(KafkaScenarioTopologyConfig.TOPIC_ORDER);

        // 1. 验证宏常量标识自洽
        assertThat(KafkaConstants.LOG_SEND_PREFIX).isEqualTo("[MQ-SEND]");
        assertThat(KafkaConstants.LOG_CONSUME_PREFIX).isEqualTo("[MQ-CONSUME]");
        assertThat(KafkaConstants.LOG_CONSUME_BATCH_PREFIX).isEqualTo("[MQ-CONSUME-BATCH]");
        assertThat(KafkaConstants.LOG_DLT_FORWARD_PREFIX).isEqualTo("[MQ-DLT-FORWARD]");

        // 2. 验证消费容器工厂开启了 Micrometer Observation 可观测性追踪
        assertThat(defaultKafkaListenerContainerFactory).isNotNull();
        assertThat(defaultKafkaListenerContainerFactory.getContainerProperties().isObservationEnabled()).isTrue();
        assertThat(batchKafkaListenerContainerFactory).isNotNull();
        assertThat(batchKafkaListenerContainerFactory.getContainerProperties().isObservationEnabled()).isTrue();
    }

    @TestConfiguration
    static class TestKafkaConfig {

        private final AtomicLong offsetSequence = new AtomicLong(100L);

        @Bean
        @Primary
        public org.springframework.kafka.core.KafkaAdmin mockKafkaAdmin() {
            return mock(org.springframework.kafka.core.KafkaAdmin.class);
        }

        @Bean
        public io.micrometer.observation.ObservationRegistry observationRegistry() {
            return io.micrometer.observation.ObservationRegistry.create();
        }

        @Bean
        @Primary
        @SuppressWarnings("unchecked")
        public KafkaTemplate<Object, Object> mockKafkaTemplate(
                org.springframework.beans.factory.ObjectProvider<OrderSingleRecordConsumer> orderConsumerProvider,
                org.springframework.beans.factory.ObjectProvider<PoisonPillKafkaConsumer> poisonConsumerProvider,
                org.springframework.beans.factory.ObjectProvider<DeadLetterAuditConsumer> deadLetterAuditConsumerProvider) {
            KafkaTemplate<Object, Object> mockTemplate = mock(KafkaTemplate.class);

            when(mockTemplate.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
                ProducerRecord<Object, Object> record = invocation.getArgument(0);
                int partition = record.partition() != null ? record.partition() : 0;
                long offset = offsetSequence.incrementAndGet();

                RecordMetadata metadata = new RecordMetadata(
                        new TopicPartition(record.topic(), partition),
                        offset, 0, System.currentTimeMillis(), 0, 0
                );
                SendResult<Object, Object> sendResult = new SendResult<>(record, metadata);

                // 模拟单条消息同步分发给监听器消费
                if (KafkaScenarioTopologyConfig.TOPIC_ORDER.equals(record.topic())) {
                    if (record.value() instanceof OrderMessage orderMsg) {
                        String key = record.key() != null ? record.key().toString() : null;
                        OrderSingleRecordConsumer consumer = orderConsumerProvider.getIfAvailable();
                        if (consumer != null) {
                            consumer.onOrderMessage(orderMsg, key, partition, offset);
                        }
                    }
                } else if (KafkaScenarioTopologyConfig.TOPIC_POISON.equals(record.topic())) {
                    if (record.value() instanceof String str) {
                        PoisonPillKafkaConsumer consumer = poisonConsumerProvider.getIfAvailable();
                        if (consumer != null) {
                            try {
                                consumer.onPoisonMessage(str);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                } else if (record.topic() != null && record.topic().endsWith(".DLT")) {
                    DeadLetterAuditConsumer consumer = deadLetterAuditConsumerProvider.getIfAvailable();
                    if (consumer != null) {
                        ConsumerRecord<Object, Object> cr = new ConsumerRecord<>(
                                record.topic(), partition, offset, record.key(), record.value()
                        );
                        consumer.onDeadLetter(cr, record.topic(), "模拟死信转发异常".getBytes(StandardCharsets.UTF_8));
                    }
                }

                return CompletableFuture.completedFuture(sendResult);
            });

            return mockTemplate;
        }

        @Bean
        @Primary
        public PlatformTransactionManager testTransactionManager() {
            return new AbstractPlatformTransactionManager() {
                @Override
                protected Object doGetTransaction() {
                    return new Object();
                }

                @Override
                protected void doBegin(Object transaction, TransactionDefinition definition) {
                }

                @Override
                protected void doCommit(DefaultTransactionStatus status) {
                }

                @Override
                protected void doRollback(DefaultTransactionStatus status) {
                }
            };
        }
    }
}
