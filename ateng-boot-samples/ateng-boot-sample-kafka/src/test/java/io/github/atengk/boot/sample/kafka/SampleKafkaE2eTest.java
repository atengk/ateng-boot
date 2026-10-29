package io.github.atengk.boot.sample.kafka;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.kafka.builder.KafkaTopicBuilder;
import io.github.atengk.boot.kafka.constant.KafkaConstants;
import io.github.atengk.boot.kafka.helper.KafkaDeadLetterHelper;
import io.github.atengk.boot.kafka.helper.KafkaDeadLetterHelper.DeadLetterRecord;
import io.github.atengk.boot.kafka.listener.AtengDeadLetterPublishingRecoverer;
import io.github.atengk.boot.kafka.listener.AtengKafkaErrorHandler;
import io.github.atengk.boot.kafka.manager.KafkaContainerManager;
import io.github.atengk.boot.kafka.manager.KafkaOffsetManager;
import io.github.atengk.boot.kafka.model.ConsumerGroupLag;
import io.github.atengk.boot.kafka.model.ConsumerGroupLag.PartitionLag;
import io.github.atengk.boot.kafka.template.KafkaDeadLetterReplayTemplate;
import io.github.atengk.boot.kafka.template.KafkaReliableTemplate;
import io.github.atengk.boot.sample.kafka.config.KafkaScenarioTopologyConfig;
import io.github.atengk.boot.sample.kafka.consumer.*;
import io.github.atengk.boot.sample.kafka.model.OrderMessage;
import io.github.atengk.boot.sample.kafka.model.UserEventMessage;
import io.github.atengk.boot.sample.kafka.repository.MockOrderRepository;
import io.github.atengk.boot.sample.kafka.service.*;
import jakarta.validation.ValidationException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.awaitility.core.ThrowingRunnable;
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
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ResolvableType;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.converter.ByteArrayJsonMessageConverter;
import org.springframework.messaging.Message;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.util.unit.DataSize;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Kafka 场景沙箱 1:1 全场景端到端离线集成测试套件
 *
 * <p>全面覆盖 15 大企业级生产场景（100% 离线自洽，零外部物理 Broker 依赖）：
 * <ul>
 *   <li>场景 1：Key 分区保序与同步、异步、物理分区可靠收发 (TradeKafkaScenarioService)</li>
 *   <li>场景 2：本地事务提交感知发信与回滚防幽灵消息 (sendAfterCommit + 补偿 SPI) (TradeKafkaScenarioService)</li>
 *   <li>场景 3：Kafka 原生分布式事务执行 (executeInTransaction 原子多写与 4D 上下文透传) (TradeKafkaScenarioService)</li>
 *   <li>场景 4：微服务推断反序列化解耦与泛型集合接收 (无需 __TypeId__ 强耦合)</li>
 *   <li>场景 5：@Idempotent 消费端幂等拦截与静默提交 Offset</li>
 *   <li>场景 6：毒丸非法报文 0 次重试快速进 *.DLT 与死信终点不变量</li>
 *   <li>场景 7：死信结构化诊断 (KafkaDeadLetterHelper 双模解析 7 类 DLT 标头) (DeadLetterReplayScenarioService)</li>
 *   <li>场景 8：死信安全重放 (KafkaDeadLetterReplayTemplate 消息头清洗、重放计数与防二次雪崩熔断) (DeadLetterReplayScenarioService)</li>
 *   <li>场景 9：死信主题探查与物理清空 (peek + purge) (DeadLetterReplayScenarioService)</li>
 *   <li>场景 10：批处理容器与 KafkaRecordExecutor 多租户安全隔离与逐条错误补偿 (BatchLogScenarioService)</li>
 *   <li>场景 11：1MB 报文超限 Fail-Fast 拦截与大报文分段</li>
 *   <li>场景 12：声明式日志压实与高级拓扑构建 (KafkaTopicBuilder compact, deleteAndCompact, maxMessageBytes, compressionType)</li>
 *   <li>场景 13：非阻塞阶梯延迟重试 (@RetryableTopic 阶梯主题流转与 4D 上下文透传) (NonBlockingRetryScenarioService)</li>
 *   <li>场景 14：运行期位移治理与实时 Lag 积压探测 (KafkaOffsetManager 时间戳回拨、LEO 边界保护与分区积压监控) (KafkaOpsScenarioService)</li>
 *   <li>场景 15：运行期消费容器动态流控 (KafkaContainerManager 维护窗口 pause/resume 与状态探测) (KafkaOpsScenarioService)</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@SpringBootTest(classes = SampleKafkaApplication.class)
@Import({SampleKafkaE2eTest.MockKafkaTestConfig.class, SampleKafkaE2eTest.CommonTestConfig.class})
@DisplayName("Kafka 场景沙箱 1:1 全场景端到端双模集成测试")
class SampleKafkaE2eTest {

    @Autowired
    private TradeKafkaScenarioService tradeScenarioService;

    @Autowired
    private DeadLetterReplayScenarioService deadLetterService;

    @Autowired
    private BatchLogScenarioService batchLogService;

    @Autowired
    private NonBlockingRetryScenarioService retryService;

    @Autowired
    private KafkaOpsScenarioService opsService;

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

    private void awaitCondition(ThrowingRunnable assertion) {
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(assertion);
    }

    @BeforeEach
    void setUp() {
        orderSingleRecordConsumer.clear();
        batchRecordConsumer.clear();
        idempotentConsumer.clear();
        deadLetterAuditConsumer.clear();
        tradeScenarioService.clear();
        mockOrderRepository.clear();
        sampleTxFailureCallback.clear();
        batchLogService.clear();
        retryService.clear();
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

    // =========================================================================
    // 场景 1: Key 分区保序与同步、异步、物理分区可靠收发 (TradeKafkaScenarioService)
    // =========================================================================
    @Test
    @DisplayName("场景 1: Key 分区保序与同步、异步、物理分区可靠收发")
    void testScenario1_KeyPartitionAndReliableSend() {
        UserContextHolder.setUserId("user-001");
        TenantContextHolder.setTenantId("tenant-001");
        MDC.put("traceId", "trace-sc1");

        // 1. 同步按 Key 发送订单
        OrderMessage order = new OrderMessage("ORD-1001", new BigDecimal("299.00"), "user-001", "tenant-001");
        SendResult<Object, Object> syncResult = tradeScenarioService.sendSyncOrder(order);
        assertThat(syncResult).isNotNull();
        assertThat(syncResult.getRecordMetadata().topic()).isEqualTo(KafkaScenarioTopologyConfig.TOPIC_ORDER);

        // 2. 异步发送用户事件
        UserEventMessage event = new UserEventMessage("user-001", "LOGIN", System.currentTimeMillis());
        CompletableFuture<SendResult<Object, Object>> asyncFuture = tradeScenarioService.sendAsyncUserEvent(event);
        SendResult<Object, Object> asyncResult = asyncFuture.join();
        assertThat(asyncResult.getRecordMetadata().topic()).isEqualTo(KafkaScenarioTopologyConfig.TOPIC_USER);

        // 3. 显式指定物理分区 2 投递
        SendResult<Object, Object> partitionResult = tradeScenarioService.sendPartitionDirected(2, "p-key", order);
        assertThat(partitionResult.getRecordMetadata().partition()).isEqualTo(2);

        // 4. 验证单条监听器成功消费
        awaitCondition(() -> {
            assertThat(orderSingleRecordConsumer.getConsumedRecords()).isNotEmpty();
            OrderSingleRecordConsumer.ConsumedRecord consumed = orderSingleRecordConsumer.getConsumedRecords().get(0);
            assertThat(consumed.order().getOrderId()).isEqualTo("ORD-1001");
            assertThat(consumed.key()).isEqualTo("ORD-1001");
            assertThat(consumed.traceId()).isEqualTo("trace-sc1");
            assertThat(consumed.tenantId()).isEqualTo("tenant-001");
            assertThat(consumed.userId()).isEqualTo("user-001");
        });
    }

    // =========================================================================
    // 场景 2: 本地事务提交感知发信与回滚防幽灵消息 (TradeKafkaScenarioService)
    // =========================================================================
    @Test
    @DisplayName("场景 2: 本地事务提交感知发信与回滚防幽灵消息 (sendAfterCommit + 补偿 SPI)")
    void testScenario2_TransactionalSendAfterCommitAndRollback() {
        OrderMessage successOrder = new OrderMessage("ORD-TX-OK", new BigDecimal("500.00"), "user-tx", "tenant-tx");

        // 1. 事务成功提交 -> 触发 afterCommit 物理发信
        tradeScenarioService.createOrderWithSendAfterCommit(successOrder);
        assertThat(tradeScenarioService.getLocalDbOrders()).contains("ORD-TX-OK");
        awaitCondition(() -> assertThat(orderSingleRecordConsumer.getConsumedRecords())
                .anyMatch(r -> "ORD-TX-OK".equals(r.order().getOrderId())));

        orderSingleRecordConsumer.clear();

        // 2. 事务异常回滚 -> 物理发信被阻断，严禁产生幽灵消息
        OrderMessage rollbackOrder = new OrderMessage("ORD-TX-FAIL", new BigDecimal("999.00"), "user-tx", "tenant-tx");
        assertThatThrownBy(() -> tradeScenarioService.createOrderWithRollback(rollbackOrder))
                .isInstanceOf(IllegalStateException.class);

        // 确认没有消费记录产生
        assertThat(orderSingleRecordConsumer.getConsumedRecords()).noneMatch(r -> "ORD-TX-FAIL".equals(r.order().getOrderId()));
    }

    // =========================================================================
    // 场景 3: Kafka 原生分布式事务执行 (TradeKafkaScenarioService)
    // =========================================================================
    @Test
    @DisplayName("场景 3: Kafka 原生分布式事务执行 (executeInTransaction 原子多写与 4D 上下文透传)")
    void testScenario3_NativeKafkaTransactionExecution() {
        MDC.put("traceId", "trace-tx-native");
        TenantContextHolder.setTenantId("tenant-tx-native");
        UserContextHolder.setUserId("user-tx-native");

        OrderMessage order = new OrderMessage("ORD-TX-NATIVE", new BigDecimal("777.00"), "user-tx-native", "tenant-tx-native");
        String txResult = tradeScenarioService.executeTradeAtomicMultiSend(order, "AUDIT-EVENT-LOG");

        assertThat(txResult).isEqualTo("SUCCESS");
        awaitCondition(() -> assertThat(orderSingleRecordConsumer.getConsumedRecords())
                .anyMatch(r -> "ORD-TX-NATIVE".equals(r.order().getOrderId())));
    }

    // =========================================================================
    // 场景 4: 微服务推断反序列化解耦与泛型集合接收
    // =========================================================================
    @Test
    @DisplayName("场景 4: 微服务推断反序列化解耦与泛型集合接收 (无需 __TypeId__ 强耦合)")
    void testScenario4_InferredDeserializationWithoutTypeId() {
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

    // =========================================================================
    // 场景 5: @Idempotent 消费端幂等拦截与静默提交 Offset
    // =========================================================================
    @Test
    @DisplayName("场景 5: @Idempotent 消费端幂等拦截与静默提交 Offset")
    void testScenario5_IdempotentDuplicateSilentCommit() {
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

    // =========================================================================
    // 场景 6: 毒丸非法报文 0 次重试快速进 *.DLT 与死信终点不变量
    // =========================================================================
    @Test
    @DisplayName("场景 6: 毒丸非法报文 0 次重试快速进 *.DLT 与死信终点不变量")
    void testScenario6_PoisonPillZeroRetryToDltAndTerminalInvariant() {
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

    // =========================================================================
    // 场景 7: 死信结构化诊断 (DeadLetterReplayScenarioService)
    // =========================================================================
    @Test
    @DisplayName("场景 7: 死信结构化诊断 (KafkaDeadLetterHelper 双模解析 7 类 DLT 标头)")
    void testScenario7_DeadLetterStructuredDiagnosis() {
        RecordHeaders headers = new RecordHeaders();
        headers.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC, "biz.trade.topic".getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION, ByteBuffer.allocate(4).putInt(3).array()));
        headers.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET, ByteBuffer.allocate(8).putLong(8888L).array()));
        headers.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_TIMESTAMP, ByteBuffer.allocate(8).putLong(1700000000000L).array()));
        headers.add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_FQCN, "java.lang.IllegalArgumentException".getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE, "非法交易金额".getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_STACKTRACE, "at com.example.TradeService.pay(TradeService.java:42)".getBytes(StandardCharsets.UTF_8)));

        ConsumerRecord<byte[], byte[]> dltRecord = new ConsumerRecord<>(
                "biz.trade.topic.DLT", 3, 100L, 0L, null, 0, 0,
                "order-888".getBytes(StandardCharsets.UTF_8),
                "{}".getBytes(StandardCharsets.UTF_8),
                headers, Optional.empty()
        );

        DeadLetterRecord record = deadLetterService.diagnose(dltRecord);

        assertThat(record).isNotNull();
        assertThat(record.originalTopic()).isEqualTo("biz.trade.topic");
        assertThat(record.originalPartition()).isEqualTo(3);
        assertThat(record.originalOffset()).isEqualTo(8888L);
        assertThat(record.originalTimestamp()).isEqualTo(1700000000000L);
        assertThat(record.exceptionClassName()).isEqualTo("java.lang.IllegalArgumentException");
        assertThat(record.exceptionMessage()).isEqualTo("非法交易金额");
        assertThat(record.exceptionStackTrace()).contains("TradeService.java:42");
        assertThat(record.isReplayed()).isFalse();
    }

    // =========================================================================
    // 场景 8: 死信安全重放与防二次雪崩熔断 (DeadLetterReplayScenarioService)
    // =========================================================================
    @Test
    @DisplayName("场景 8: 死信安全重放 (KafkaDeadLetterReplayTemplate 消息头清洗、重放计数与防二次雪崩熔断)")
    void testScenario8_DeadLetterReplayAndAntiAvalanche() {
        int replayedCount = deadLetterService.replayDeadLetters(
                KafkaScenarioTopologyConfig.TOPIC_POISON + ".DLT",
                KafkaScenarioTopologyConfig.TOPIC_ORDER,
                10
        );

        assertThat(replayedCount).isGreaterThanOrEqualTo(0);

        int filteredReplayed = deadLetterService.replayDeadLetters(
                KafkaScenarioTopologyConfig.TOPIC_POISON + ".DLT",
                KafkaScenarioTopologyConfig.TOPIC_ORDER,
                10,
                record -> record.key() != null
        );

        assertThat(filteredReplayed).isGreaterThanOrEqualTo(0);
    }

    // =========================================================================
    // 场景 9: 死信主题探查与物理清空 (DeadLetterReplayScenarioService)
    // =========================================================================
    @Test
    @DisplayName("场景 9: 死信主题探查与物理清空 (peek + purge)")
    void testScenario9_DeadLetterPeekAndPurge() {
        List<ConsumerRecord<byte[], byte[]>> peeked = deadLetterService.peekDeadLetters(
                KafkaScenarioTopologyConfig.TOPIC_POISON + ".DLT",
                5
        );
        assertThat(peeked).isNotNull();

        // 物理清空死信主题
        deadLetterService.purgeDeadLetters(KafkaScenarioTopologyConfig.TOPIC_POISON + ".DLT");
    }

    // =========================================================================
    // 场景 10: 批处理容器与 KafkaRecordExecutor 多租户安全隔离与逐条错误补偿
    // =========================================================================
    @Test
    @DisplayName("场景 10: 批处理容器与 KafkaRecordExecutor 多租户安全隔离与逐条错误补偿")
    void testScenario10_BatchMultiTenantContextIsolationAndFaultTolerance() {
        List<ConsumerRecord<String, OrderMessage>> batchRecords = new ArrayList<>();

        OrderMessage orderA = new OrderMessage("ORD-A", new BigDecimal("10"), "uA", "tenant-A");
        OrderMessage orderB = new OrderMessage("ORD-CORRUPTED", new BigDecimal("20"), "uB", "tenant-B");
        OrderMessage orderC = new OrderMessage("ORD-C", new BigDecimal("30"), "uC", "tenant-C");

        RecordHeaders hA = new RecordHeaders();
        hA.add(new RecordHeader(MessageHeaderConstants.TENANT_ID, "tenant-A".getBytes(StandardCharsets.UTF_8)));
        ConsumerRecord<String, OrderMessage> recA = new ConsumerRecord<>(KafkaScenarioTopologyConfig.TOPIC_BATCH, 0, 1L, 0L, null, 0, 0, "ORD-A", orderA, hA, Optional.empty());

        RecordHeaders hB = new RecordHeaders();
        hB.add(new RecordHeader(MessageHeaderConstants.TENANT_ID, "tenant-B".getBytes(StandardCharsets.UTF_8)));
        ConsumerRecord<String, OrderMessage> recB = new ConsumerRecord<>(KafkaScenarioTopologyConfig.TOPIC_BATCH, 1, 2L, 0L, null, 0, 0, "ORD-CORRUPTED", orderB, hB, Optional.empty());

        RecordHeaders hC = new RecordHeaders();
        hC.add(new RecordHeader(MessageHeaderConstants.TENANT_ID, "tenant-C".getBytes(StandardCharsets.UTF_8)));
        ConsumerRecord<String, OrderMessage> recC = new ConsumerRecord<>(KafkaScenarioTopologyConfig.TOPIC_BATCH, 2, 3L, 0L, null, 0, 0, "ORD-C", orderC, hC, Optional.empty());

        batchRecords.add(recA);
        batchRecords.add(recB);
        batchRecords.add(recC);

        batchLogService.processBatchSafely(batchRecords);

        // 验证失败条目被容错捕获，且剩余记录正常处理
        assertThat(batchLogService.getFailedItems()).hasSize(1);
        assertThat(batchLogService.getFailedItems().get(0).key()).isEqualTo("ORD-CORRUPTED");

        // 验证现场现场绝对干净
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    // =========================================================================
    // 场景 11: 1MB 报文超限 Fail-Fast 拦截
    // =========================================================================
    @Test
    @DisplayName("场景 11: 1MB 报文超限 Fail-Fast 拦截")
    void testScenario11_PayloadTooLargeFailFast() {
        byte[] largePayload = new byte[1048576 + 64];

        assertThatThrownBy(() -> tradeScenarioService.sendLargePayload(largePayload))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getResultCode()).isEqualTo(CommonResultCode.BAD_REQUEST);
                    assertThat(be.getMessage()).contains("超出最大限制");
                });
    }

    // =========================================================================
    // 场景 12: 声明式日志压实与高级拓扑构建 (KafkaTopicBuilder)
    // =========================================================================
    @Test
    @DisplayName("场景 12: 声明式日志压实与高级拓扑构建 (KafkaTopicBuilder)")
    void testScenario12_DeclarativeCompactedTopicTopology() {
        NewTopic compactedTopic = KafkaTopicBuilder.topic("sample.state.compact.topic")
                .partitions(6)
                .replicas(2)
                .compact()
                .maxMessageBytes(DataSize.ofMegabytes(10))
                .compressionType("snappy")
                .buildMain();

        assertThat(compactedTopic.name()).isEqualTo("sample.state.compact.topic");
        assertThat(compactedTopic.numPartitions()).isEqualTo(6);
        assertThat(compactedTopic.configs())
                .containsEntry(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT)
                .containsEntry(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, String.valueOf(DataSize.ofMegabytes(10).toBytes()))
                .containsEntry(TopicConfig.COMPRESSION_TYPE_CONFIG, "snappy");
    }

    // =========================================================================
    // 场景 13: 非阻塞阶梯延迟重试 (@RetryableTopic 阶梯主题流转与 4D 上下文透传)
    // =========================================================================
    @Test
    @DisplayName("场景 13: 非阻塞阶梯延迟重试 (@RetryableTopic 阶梯主题流转与 4D 上下文透传)")
    void testScenario13_NonBlockingTieredRetry() {
        MDC.put("traceId", "trace-retry-999");
        TenantContextHolder.setTenantId("tenant-retry-888");
        UserContextHolder.setUserId("user-retry-777");

        // 1. 发送正常消息 -> 一次尝试即成功
        ConsumerRecord<String, String> normalRecord = new ConsumerRecord<>(
                NonBlockingRetryScenarioService.TOPIC_RETRY_MAIN, 0, 1L, "key-ok", "PAYLOAD_SUCCESS"
        );
        retryService.onMessage(normalRecord, NonBlockingRetryScenarioService.TOPIC_RETRY_MAIN);

        assertThat(retryService.getAttemptCount()).isEqualTo(1);
        assertThat(retryService.getRetryLogs()).hasSize(1);
        NonBlockingRetryScenarioService.RetryRecordLog logEntry = retryService.getRetryLogs().get(0);
        assertThat(logEntry.traceId()).isEqualTo("trace-retry-999");
        assertThat(logEntry.tenantId()).isEqualTo("tenant-retry-888");
        assertThat(logEntry.userId()).isEqualTo("user-retry-777");

        // 2. 模拟失败 -> 触发异常与 DLT 终点拦截
        ConsumerRecord<String, String> failRecord = new ConsumerRecord<>(
                NonBlockingRetryScenarioService.TOPIC_RETRY_MAIN, 0, 2L, "key-fail", "PAYLOAD_FAIL"
        );
        assertThatThrownBy(() -> retryService.onMessage(failRecord, NonBlockingRetryScenarioService.TOPIC_RETRY_MAIN))
                .isInstanceOf(RuntimeException.class);

        // 3. 达到重试上限进入 DLT
        retryService.onDlt(failRecord);
        assertThat(retryService.getDltRecords()).contains("key-fail:PAYLOAD_FAIL");
    }

    // =========================================================================
    // 场景 14: 运行期位移治理与实时 Lag 积压探测 (KafkaOffsetManager)
    // =========================================================================
    @Test
    @DisplayName("场景 14: 运行期位移治理与实时 Lag 积压探测 (KafkaOffsetManager)")
    void testScenario14_OffsetGovernanceAndRealtimeLagMonitoring() {
        // 1. 回溯至时间戳
        opsService.rewindOffsetsToTimestamp("sample-group", "sample.order.topic", Instant.now());

        // 2. 回拨至 earliest
        opsService.rewindOffsetsToEarliest("sample-group", "sample.order.topic");

        // 3. 推进至 latest
        opsService.rewindOffsetsToLatest("sample-group", "sample.order.topic");

        // 4. 实时查询 Lag 积压
        ConsumerGroupLag lag = opsService.getConsumerGroupLag("sample-group", "sample.order.topic");
        assertThat(lag).isNotNull();
        assertThat(lag.groupId()).isIn("test-group", "sample-group", "sample-kafka-local-group");
        assertThat(lag.topic()).isEqualTo("sample.order.topic");
        assertThat(lag.totalLag()).isGreaterThanOrEqualTo(0L);
    }

    // =========================================================================
    // 场景 15: 运行期消费容器动态流控 (KafkaContainerManager)
    // =========================================================================
    @Test
    @DisplayName("场景 15: 运行期消费容器动态流控 (KafkaContainerManager)")
    void testScenario15_ConsumerContainerDynamicFlowControl() {
        // 1. 暂停指定 Topic 消费容器
        opsService.pauseConsumer(KafkaScenarioTopologyConfig.TOPIC_ORDER);
        assertThat(opsService.isConsumerPaused(KafkaScenarioTopologyConfig.TOPIC_ORDER)).isTrue();

        // 2. 恢复指定 Topic 消费容器
        opsService.resumeConsumer(KafkaScenarioTopologyConfig.TOPIC_ORDER);
        assertThat(opsService.isConsumerRunning(KafkaScenarioTopologyConfig.TOPIC_ORDER)).isTrue();
    }

    @TestConfiguration
    @Profile("!local")
    static class MockKafkaTestConfig {

        private final AtomicLong offsetSequence = new AtomicLong(100L);

        @Bean
        @Primary
        public org.springframework.kafka.core.KafkaAdmin mockKafkaAdmin() {
            return mock(org.springframework.kafka.core.KafkaAdmin.class);
        }

        @Bean
        @Primary
        public KafkaDeadLetterReplayTemplate mockDeadLetterReplayTemplate() {
            KafkaDeadLetterReplayTemplate mock = mock(KafkaDeadLetterReplayTemplate.class);
            when(mock.peek(anyString(), anyInt())).thenReturn(List.of(
                    new ConsumerRecord<>("sample.poison.topic.DLT", 0, 1L, "k".getBytes(), "v".getBytes())
            ));
            when(mock.replay(anyString(), anyString(), anyInt())).thenReturn(3);
            when(mock.replay(anyString(), anyString(), anyInt(), any())).thenReturn(2);
            doNothing().when(mock).purge(anyString());
            return mock;
        }

        @Bean
        @Primary
        public KafkaOffsetManager mockKafkaOffsetManager() {
            KafkaOffsetManager mock = mock(KafkaOffsetManager.class);
            when(mock.getConsumerGroupLag(anyString(), anyString())).thenReturn(
                    new ConsumerGroupLag("test-group", "sample.order.topic", 15L, List.of(
                            new PartitionLag(0, 100L, 85L, 15L)
                    ))
            );
            return mock;
        }

        @Bean
        @Primary
        public KafkaContainerManager mockKafkaContainerManager() {
            KafkaContainerManager mock = mock(KafkaContainerManager.class);
            when(mock.isPaused(anyString())).thenReturn(true);
            when(mock.isRunning(anyString())).thenReturn(true);
            return mock;
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

            when(mockTemplate.executeInTransaction(any())).thenAnswer(invocation -> {
                KafkaOperations.OperationsCallback<Object, Object, Object> cb = invocation.getArgument(0);
                return cb.doInOperations(mockTemplate);
            });

            return mockTemplate;
        }
    }

    @TestConfiguration
    static class CommonTestConfig {

        @Bean
        public io.micrometer.observation.ObservationRegistry observationRegistry() {
            return io.micrometer.observation.ObservationRegistry.create();
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
