package io.github.atengk.boot.sample.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.github.atengk.boot.core.validation.ValidList;
import io.github.atengk.boot.sample.kafka.config.KafkaScenarioTopologyConfig;
import io.github.atengk.boot.sample.kafka.consumer.*;
import io.github.atengk.boot.sample.kafka.dto.BatchOrderProduceRequest;
import io.github.atengk.boot.sample.kafka.dto.ContainerActionRequest;
import io.github.atengk.boot.sample.kafka.dto.ContainerActionRequest.ContainerAction;
import io.github.atengk.boot.sample.kafka.dto.ContainerScaleRequest;
import io.github.atengk.boot.sample.kafka.dto.DeadLetterReplayRequest;
import io.github.atengk.boot.sample.kafka.dto.NativeTxProduceRequest;
import io.github.atengk.boot.sample.kafka.dto.OffsetResetRequest;
import io.github.atengk.boot.sample.kafka.dto.OffsetResetRequest.ResetType;
import io.github.atengk.boot.sample.kafka.dto.OrderProduceRequest;
import io.github.atengk.boot.sample.kafka.dto.RetryTriggerRequest;
import io.github.atengk.boot.sample.kafka.dto.UserEventProduceRequest;
import io.github.atengk.boot.sample.kafka.model.OrderMessage;
import io.github.atengk.boot.sample.kafka.model.UserEventMessage;
import io.github.atengk.boot.sample.kafka.offline.OfflineKafkaBroker;
import io.github.atengk.boot.sample.kafka.repository.MockOrderRepository;
import io.github.atengk.boot.sample.kafka.service.*;
import jakarta.validation.ValidationException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.converter.ByteArrayJsonMessageConverter;
import org.springframework.messaging.Message;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.util.unit.DataSize;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kafka 场景沙箱 1:1 全场景端到端离线集成测试套件
 *
 * <p>全面覆盖 15 大企业级生产场景（默认 {@code !local} 经由 {@code OfflineKafkaConfiguration} 内存驱动全真执行，{@code local} Profile 直连外部物理 Broker）：
 * <ul>
 *   <li>场景 1：Key 分区保序与同步、异步、物理分区可靠收发 (KafkaProduceController Web 闭环)</li>
 *   <li>场景 2：本地事务提交感知发信与回滚防幽灵消息 (sendAfterCommit + 补偿 SPI) (KafkaProduceController Web 闭环)</li>
 *   <li>场景 3：Kafka 原生分布式事务执行 (executeInTransaction 原子多写与 4D 上下文透传) (KafkaProduceController Web 闭环)</li>
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
@AutoConfigureMockMvc
@Import(SampleKafkaE2eTest.CommonTestConfig.class)
@DisplayName("Kafka 场景沙箱 1:1 全场景端到端双模集成测试")
class SampleKafkaE2eTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @Autowired
    private KafkaContainerManager kafkaContainerManager;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Autowired(required = false)
    private OfflineKafkaBroker offlineKafkaBroker;

    @Autowired
    private MockOrderRepository mockOrderRepository;

    @Autowired
    @Qualifier(KafkaConstants.DEFAULT_CONTAINER_FACTORY)
    private ConcurrentKafkaListenerContainerFactory<?, ?> defaultKafkaListenerContainerFactory;

    @Autowired
    @Qualifier(KafkaConstants.BATCH_CONTAINER_FACTORY)
    private ConcurrentKafkaListenerContainerFactory<?, ?> batchKafkaListenerContainerFactory;

    private final Map<String, Integer> initialConcurrencies = new LinkedHashMap<>();

    private void awaitCondition(ThrowingRunnable assertion) {
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(assertion);
    }

    @BeforeEach
    void setUp() {
        captureInitialConcurrencies();
        restoreAllContainers();
        if (offlineKafkaBroker != null) {
            offlineKafkaBroker.reset();
        }
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
        restoreAllContainers();
        TransactionSynchronizationManager.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    private void captureInitialConcurrencies() {
        if (!initialConcurrencies.isEmpty() || kafkaListenerEndpointRegistry == null) {
            return;
        }
        for (MessageListenerContainer container : kafkaListenerEndpointRegistry.getListenerContainers()) {
            String listenerId = container.getListenerId();
            if (listenerId != null && container instanceof ConcurrentMessageListenerContainer<?, ?> concurrent) {
                initialConcurrencies.put(listenerId, concurrent.getConcurrency());
            }
        }
    }

    private void restoreAllContainers() {
        if (kafkaListenerEndpointRegistry == null) {
            return;
        }
        for (MessageListenerContainer container : kafkaListenerEndpointRegistry.getListenerContainers()) {
            String listenerId = container.getListenerId();
            if (listenerId == null) {
                continue;
            }
            Integer expectedConcurrency = initialConcurrencies.get(listenerId);
            if (expectedConcurrency != null
                    && container instanceof ConcurrentMessageListenerContainer<?, ?> concurrent
                    && concurrent.getConcurrency() != expectedConcurrency) {
                kafkaContainerManager.scaleConcurrency(listenerId, expectedConcurrency);
            }
            if (!container.isRunning()) {
                kafkaContainerManager.start(listenerId);
            }
            if (container.isPauseRequested()) {
                kafkaContainerManager.resume(listenerId);
            }
        }
    }

    // =========================================================================
    // 场景 1: Key 分区保序与同步、异步、物理分区可靠收发 (KafkaProduceController Web 闭环)
    // =========================================================================
    @Test
    @DisplayName("场景 1: Key 分区保序与同步、异步、物理分区可靠收发 (KafkaProduceController Web 闭环)")
    void testScenario1_KeyPartitionAndReliableSend() throws Exception {
        UserContextHolder.setUserId("user-001");
        TenantContextHolder.setTenantId("tenant-001");
        MDC.put("traceId", "trace-sc1");

        // 1. HTTP 同步按 Key 发送订单（验证自定义业务头透传与系统保留头防篡改）
        OrderProduceRequest syncReq = OrderProduceRequest.builder()
                .orderId("ORD-1001")
                .amount(new BigDecimal("299.00"))
                .userId("user-001")
                .tenantId("tenant-001")
                .customHeaders(java.util.Map.of(
                        "X-Biz-Source", "web-checkout",
                        "X-Trace-Id", "spoofed-trace-ignored"
                ))
                .build();

        mockMvc.perform(post("/sample/kafka/produce/order-sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(syncReq))
                        .header("X-Trace-Id", "trace-sc1")
                        .header("X-Tenant-Id", "tenant-001")
                        .header("X-User-Id", "user-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true));

        // 2. HTTP 异步发送用户事件
        UserEventProduceRequest eventReq = UserEventProduceRequest.builder()
                .userId("user-001")
                .eventType("LOGIN")
                .build();

        mockMvc.perform(post("/sample/kafka/produce/user-event-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(eventReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true));

        // 3. HTTP 显式指定物理分区 2 投递
        OrderProduceRequest partitionReq = OrderProduceRequest.builder()
                .orderId("ORD-PARTITION")
                .amount(new BigDecimal("199.00"))
                .userId("user-001")
                .tenantId("tenant-001")
                .partition(2)
                .key("p-key")
                .build();

        mockMvc.perform(post("/sample/kafka/produce/order-sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(partitionReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.partition").value(2))
                .andExpect(jsonPath("$.data.summary").value(org.hamcrest.Matchers.containsString("PARTITION=2")));

        // 4. HTTP 探查单条监听器成功消费并校验四维上下文与自定义头
        awaitCondition(() -> {
            mockMvc.perform(get("/sample/kafka/produce/received-orders"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data[?(@.order.orderId == 'ORD-1001' && @.traceId == 'trace-sc1' && @.customHeaders['X-Biz-Source'] == 'web-checkout')]").exists())
                    .andExpect(jsonPath("$.data[?(@.order.orderId == 'ORD-PARTITION' && @.partition == 2)]").exists());
        });
    }

    // =========================================================================
    // 场景 2: 本地事务提交感知发信与回滚防幽灵消息 (KafkaProduceController Web 闭环)
    // =========================================================================
    @Test
    @DisplayName("场景 2: 本地事务提交感知发信与回滚防幽灵消息 (KafkaProduceController Web 闭环)")
    void testScenario2_TransactionalSendAfterCommitAndRollback() throws Exception {
        // 1. HTTP 本地事务成功提交 -> 触发 afterCommit 物理发信（验证自定义业务头同步透传）
        OrderProduceRequest successOrder = OrderProduceRequest.builder()
                .orderId("ORD-TX-OK")
                .amount(new BigDecimal("500.00"))
                .userId("user-tx")
                .tenantId("tenant-tx")
                .customHeaders(java.util.Map.of("X-Tx-Source", "order-commit"))
                .build();

        mockMvc.perform(post("/sample/kafka/produce/order-tx-commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(successOrder)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("ORD-TX-OK"));

        // HTTP 探查本地 DB 订单落库
        mockMvc.perform(get("/sample/kafka/produce/db-orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0]").value("ORD-TX-OK"));

        awaitCondition(() -> {
            mockMvc.perform(get("/sample/kafka/produce/received-orders"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.order.orderId == 'ORD-TX-OK' && @.customHeaders['X-Tx-Source'] == 'order-commit')]").exists());
        });

        // 清理现场
        mockMvc.perform(post("/sample/kafka/produce/clear")).andExpect(status().isOk());

        // 2. HTTP 本地事务异常回滚 -> 物理发信被阻断，严禁产生幽灵消息
        OrderProduceRequest rollbackOrder = OrderProduceRequest.builder()
                .orderId("ORD-TX-FAIL")
                .amount(new BigDecimal("999.00"))
                .userId("user-tx")
                .tenantId("tenant-tx")
                .build();

        mockMvc.perform(post("/sample/kafka/produce/order-tx-rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rollbackOrder)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 确认没有消费记录产生
        mockMvc.perform(get("/sample/kafka/produce/received-orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[?(@.order.orderId == 'ORD-TX-FAIL')]").doesNotExist());
    }

    // =========================================================================
    // 场景 3: Kafka 原生分布式事务执行 (KafkaProduceController Web 闭环)
    // =========================================================================
    @Test
    @DisplayName("场景 3: Kafka 原生分布式事务执行 (KafkaProduceController Web 闭环)")
    void testScenario3_NativeKafkaTransactionExecution() throws Exception {
        MDC.put("traceId", "trace-tx-native");
        TenantContextHolder.setTenantId("tenant-tx-native");
        UserContextHolder.setUserId("user-tx-native");

        OrderProduceRequest orderReq = OrderProduceRequest.builder()
                .orderId("ORD-TX-NATIVE")
                .amount(new BigDecimal("777.00"))
                .userId("user-tx-native")
                .tenantId("tenant-tx-native")
                .build();

        NativeTxProduceRequest nativeTxReq = NativeTxProduceRequest.builder()
                .order(orderReq)
                .auditContent("AUDIT-EVENT-LOG")
                .build();

        mockMvc.perform(post("/sample/kafka/produce/order-native-tx")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(nativeTxReq))
                        .header("X-Trace-Id", "trace-tx-native")
                        .header("X-Tenant-Id", "tenant-tx-native")
                        .header("X-User-Id", "user-tx-native"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("SUCCESS"));

        awaitCondition(() -> {
            mockMvc.perform(get("/sample/kafka/produce/received-orders"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.order.orderId == 'ORD-TX-NATIVE')]").exists());
        });
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
    // 场景 5: @Idempotent 消费端幂等拦截与静默提交 Offset (KafkaDeadLetterController Web 闭环)
    // =========================================================================
    @Test
    @DisplayName("场景 5: @Idempotent 消费端幂等拦截与静默提交 Offset (KafkaDeadLetterController Web 闭环)")
    void testScenario5_IdempotentDuplicateSilentCommit() throws Exception {
        OrderProduceRequest orderReq = OrderProduceRequest.builder()
                .orderId("ORD-IDEMP-001")
                .amount(new BigDecimal("88.00"))
                .userId("u-1")
                .tenantId("t-1")
                .build();

        // 1. HTTP 首次投递：正常调用监听方法，消费成功
        mockMvc.perform(post("/sample/kafka/dead-letter/trigger-idempotent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.containsString("首次消费成功")));

        assertThat(idempotentConsumer.getProcessedOrderIds()).contains("ORD-IDEMP-001");
        assertThat(idempotentConsumer.getIdempotentHitCount().get()).isEqualTo(0);

        // 2. HTTP 第二次投递相同订单：触发幂等防重拦截并静默提交位移
        mockMvc.perform(post("/sample/kafka/dead-letter/trigger-idempotent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.containsString("幂等防重拦截成功")));

        assertThat(idempotentConsumer.getIdempotentHitCount().get()).isEqualTo(1);

        // 3. 验证底层 Recoverer 对 IdempotentException 返回 null 执行静默提交且不进入 DLT
        OrderMessage order = orderReq.toOrderMessage();
        ConsumerRecord<Object, Object> record = new ConsumerRecord<>(
                KafkaScenarioTopologyConfig.TOPIC_ORDER, 0, 50L, "ORD-IDEMP-001", order
        );
        TopicPartition destination = recoverer.resolveDestination(record, new IdempotentException("重复订单"));
        assertThat(destination).isNull(); // 返回 null 触发 DefaultErrorHandler 静默提交 Offset
    }

    // =========================================================================
    // 场景 6: 毒丸非法报文 0 次重试快速进 *.DLT 与死信终点不变量 (KafkaDeadLetterController Web 闭环)
    // =========================================================================
    @Test
    @DisplayName("场景 6: 毒丸非法报文 0 次重试快速进 *.DLT 与死信终点不变量 (KafkaDeadLetterController Web 闭环)")
    void testScenario6_PoisonPillZeroRetryToDltAndTerminalInvariant() throws Exception {
        // 1. HTTP 投递畸形毒丸报文
        mockMvc.perform(post("/sample/kafka/dead-letter/trigger-poison")
                        .param("poisonData", "CORRUPTED_POISON"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true));

        // 2. 验证错误处理器配置 0 次重试 (FatalExceptionClassifier 判定)
        ValidationException poisonException = new ValidationException("报文格式非法");
        ConsumerRecord<Object, Object> poisonRecord = new ConsumerRecord<>(
                KafkaScenarioTopologyConfig.TOPIC_POISON, 0, 99L, "poison-k", "CORRUPTED_POISON"
        );
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

        // 5. HTTP 探查死信诊断记录 (7 类元数据结构化诊断)
        awaitCondition(() -> {
            mockMvc.perform(get("/sample/kafka/dead-letter/audit-records"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data[0].originalTopic").value(KafkaScenarioTopologyConfig.TOPIC_POISON))
                    .andExpect(jsonPath("$.data[0].exceptionClassName").value(ListenerExecutionFailedException.class.getName()))
                    .andExpect(jsonPath("$.data[0].exceptionStackTrace").value(org.hamcrest.Matchers.containsString(ValidationException.class.getName())))
                    .andExpect(jsonPath("$.data[0].exceptionMessage").value(org.hamcrest.Matchers.containsString("报文格式非法")));
        });
    }

    // =========================================================================
    // 场景 7: 死信结构化诊断 (KafkaDeadLetterHelper 双模解析 7 类 DLT 标头)
    // =========================================================================
    @Test
    @DisplayName("场景 7: 死信结构化诊断 (KafkaDeadLetterHelper 双模解析 7 类 DLT 标头)")
    void testScenario7_DeadLetterStructuredDiagnosis() throws Exception {
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

        // 验证 Web 端点查询审计记录可用
        mockMvc.perform(get("/sample/kafka/dead-letter/audit-records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // =========================================================================
    // 场景 8: 死信安全重放 (KafkaDeadLetterController Web 闭环)
    // =========================================================================
    @Test
    @DisplayName("场景 8: 死信安全重放 (KafkaDeadLetterController Web 闭环)")
    void testScenario8_DeadLetterReplayAndAntiAvalanche() throws Exception {
        String dltTopic = KafkaScenarioTopologyConfig.TOPIC_POISON + ".DLT";
        for (int i = 1; i <= 3; i++) {
            OrderMessage payload = new OrderMessage("ORD-DLT-" + i, new BigDecimal("88.00"), "u1", "t1");
            kafkaTemplate.send(dltTopic, "dlt-key-" + i, payload).get();
        }
        awaitCondition(() -> assertThat(deadLetterService.peekDeadLetters(dltTopic, 10)).hasSize(3));

        DeadLetterReplayRequest replayReq = DeadLetterReplayRequest.builder()
                .dlqTopic(dltTopic)
                .targetTopic(KafkaScenarioTopologyConfig.TOPIC_ORDER)
                .limit(10)
                .build();

        // 1. HTTP 发起死信受控清洗重放
        mockMvc.perform(post("/sample/kafka/dead-letter/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replayReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(3));

        // 2. 带谓词过滤的安全重放校验（仅重放 key 非空的记录）
        OrderMessage filteredKeep = new OrderMessage("ORD-DLT-KEEP", BigDecimal.TEN, "u1", "t1");
        OrderMessage filteredDrop = new OrderMessage("ORD-DLT-DROP", BigDecimal.ONE, "u1", "t1");
        kafkaTemplate.send(dltTopic, "keep-key", filteredKeep).get();
        kafkaTemplate.send(dltTopic, null, filteredDrop).get();

        int filteredReplayed = deadLetterService.replayDeadLetters(
                dltTopic,
                KafkaScenarioTopologyConfig.TOPIC_ORDER,
                10,
                record -> record.key() != null
        );
        assertThat(filteredReplayed).isEqualTo(1);
    }

    // =========================================================================
    // 场景 9: 死信主题探查与物理清空 (KafkaDeadLetterController Web 闭环)
    // =========================================================================
    @Test
    @DisplayName("场景 9: 死信主题探查、结构化诊断窥视与物理清空 (KafkaDeadLetterController Web 闭环)")
    void testScenario9_DeadLetterPeekAndPurge() throws Exception {
        String dltTopic = KafkaScenarioTopologyConfig.TOPIC_POISON + ".DLT";
        OrderMessage sampleDlt = new OrderMessage("ORD-PEEK-001", new BigDecimal("19.90"), "u1", "t1");
        RecordHeaders dltHeaders = new RecordHeaders();
        dltHeaders.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC, KafkaScenarioTopologyConfig.TOPIC_ORDER.getBytes(StandardCharsets.UTF_8)));
        dltHeaders.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION, ByteBuffer.allocate(4).putInt(1).array()));
        dltHeaders.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET, ByteBuffer.allocate(8).putLong(42L).array()));
        dltHeaders.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_TIMESTAMP, ByteBuffer.allocate(8).putLong(1700000000000L).array()));
        dltHeaders.add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_FQCN, IllegalArgumentException.class.getName().getBytes(StandardCharsets.UTF_8)));
        dltHeaders.add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE, "模拟业务非法状态".getBytes(StandardCharsets.UTF_8)));
        dltHeaders.add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_STACKTRACE, "at Sample.handle(Sample.java:18)".getBytes(StandardCharsets.UTF_8)));
        kafkaTemplate.send(new ProducerRecord<>(dltTopic, null, null, "peek-key-1", sampleDlt, dltHeaders)).get();
        awaitCondition(() -> assertThat(deadLetterService.peekDeadLetters(dltTopic, 5)).hasSize(1));

        // 1. HTTP 只读探查死信队列 (不推进位移)
        mockMvc.perform(get("/sample/kafka/dead-letter/peek")
                        .param("dltTopic", dltTopic)
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1));

        // 2. HTTP 只读诊断窥视死信队列（穿透真实 KafkaDeadLetterReplayTemplate#peekDiagnosed）
        mockMvc.perform(get("/sample/kafka/dead-letter/peek-diagnosed")
                        .param("dltTopic", dltTopic)
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].originalTopic").value(KafkaScenarioTopologyConfig.TOPIC_ORDER))
                .andExpect(jsonPath("$.data[0].originalPartition").value(1))
                .andExpect(jsonPath("$.data[0].originalOffset").value(42))
                .andExpect(jsonPath("$.data[0].exceptionClassName").value(IllegalArgumentException.class.getName()))
                .andExpect(jsonPath("$.data[0].exceptionMessage").value("模拟业务非法状态"))
                .andExpect(jsonPath("$.data[0].key").value("peek-key-1"))
                .andExpect(jsonPath("$.data[0].payload").value(org.hamcrest.Matchers.containsString("ORD-PEEK-001")));

        // 3. 非法参数 JSR-303 快速失败校验
        mockMvc.perform(get("/sample/kafka/dead-letter/peek-diagnosed")
                        .param("dltTopic", "  ")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(get("/sample/kafka/dead-letter/peek-diagnosed")
                        .param("dltTopic", dltTopic)
                        .param("limit", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));

        // 4. HTTP 物理清空死信主题并验证二次探查与诊断均为空
        mockMvc.perform(post("/sample/kafka/dead-letter/purge")
                        .param("dltTopic", dltTopic))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/sample/kafka/dead-letter/peek")
                        .param("dltTopic", dltTopic)
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(get("/sample/kafka/dead-letter/peek-diagnosed")
                        .param("dltTopic", dltTopic)
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // =========================================================================
    // 场景 10: 批处理容器与 KafkaRecordExecutor 多租户安全隔离与逐条错误补偿 (KafkaBatchController Web 闭环)
    // =========================================================================
    @Test
    @DisplayName("场景 10: 批处理容器与 KafkaRecordExecutor 多租户安全隔离与逐条错误补偿 (KafkaBatchController Web 闭环)")
    void testScenario10_BatchMultiTenantContextIsolationAndFaultTolerance() throws Exception {
        // 1. HTTP 聚合发送微批多租户订单
        List<OrderProduceRequest> orders = List.of(
                OrderProduceRequest.builder().orderId("ORD-BATCH-1").amount(new BigDecimal("10.00")).userId("uA").tenantId("tenant-A").build(),
                OrderProduceRequest.builder().orderId("ORD-BATCH-2").amount(new BigDecimal("20.00")).userId("uB").tenantId("tenant-B").build(),
                OrderProduceRequest.builder().orderId("ORD-BATCH-3").amount(new BigDecimal("30.00")).userId("uC").tenantId("tenant-C").build()
        );
        BatchOrderProduceRequest batchReq = BatchOrderProduceRequest.builder()
                .orders(new ValidList<>(orders))
                .build();

        mockMvc.perform(post("/sample/kafka/batch/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batchReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(3));

        // 2. HTTP 探查批量隔离执行器安全处理的多租户日志明细
        awaitCondition(() -> {
            mockMvc.perform(get("/sample/kafka/batch/received-logs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data[?(@.order.orderId == 'ORD-BATCH-1')].tenantId").value("tenant-A"))
                    .andExpect(jsonPath("$.data[?(@.order.orderId == 'ORD-BATCH-2')].tenantId").value("tenant-B"))
                    .andExpect(jsonPath("$.data[?(@.order.orderId == 'ORD-BATCH-3')].tenantId").value("tenant-C"));
        });

        // 验证当前线程无租户泄漏污染
        assertThat(TenantContextHolder.getTenantId()).isNull();

        // 3. 验证 KafkaRecordExecutor 逐条错误补偿不阻断剩余记录
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

    @Test
    @DisplayName("场景 10B: 异步批量投递与事务提交后批量发信端到端闭环 (KafkaBatchController Web 闭环)")
    void testScenario10b_BatchAsyncAndTxCommitEndToEnd() throws Exception {
        // 1. HTTP 异步批量发送订单 -> 返回订单编号并完成异步投递消费（穿透四维上下文）
        TenantContextHolder.setTenantId("tenant-async-1");
        UserContextHolder.setUserId("user-async-1");
        MDC.put("traceId", "trace-batch-async-e2e");

        List<OrderProduceRequest> asyncOrders = List.of(
                OrderProduceRequest.builder().orderId("ORD-BASYNC-1").amount(new BigDecimal("11.00")).userId("u1").tenantId("tenant-async-1").build(),
                OrderProduceRequest.builder().orderId("ORD-BASYNC-2").amount(new BigDecimal("22.00")).userId("u2").tenantId("tenant-async-1").build()
        );
        BatchOrderProduceRequest asyncReq = BatchOrderProduceRequest.builder()
                .orders(new ValidList<>(asyncOrders))
                .build();

        mockMvc.perform(post("/sample/kafka/batch/orders-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(asyncReq))
                        .header("X-Trace-Id", "trace-batch-async-e2e")
                        .header("X-Tenant-Id", "tenant-async-1")
                        .header("X-User-Id", "user-async-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0]").value("ORD-BASYNC-1"))
                .andExpect(jsonPath("$.data[1]").value("ORD-BASYNC-2"));

        awaitCondition(() -> {
            mockMvc.perform(get("/sample/kafka/batch/received-logs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.order.orderId == 'ORD-BASYNC-1' && @.tenantId == 'tenant-async-1')]").exists())
                    .andExpect(jsonPath("$.data[?(@.order.orderId == 'ORD-BASYNC-2' && @.tenantId == 'tenant-async-1')]").exists());
        });

        mockMvc.perform(post("/sample/kafka/batch/clear")).andExpect(status().isOk());

        // 2. HTTP 事务提交后批量发信 -> 本地落库与 Kafka 批量投递消费原子完成（穿透四维上下文）
        TenantContextHolder.setTenantId("tenant-tx-batch");
        UserContextHolder.setUserId("user-tx-batch");
        MDC.put("traceId", "trace-batch-tx-e2e");

        List<OrderProduceRequest> txOrders = List.of(
                OrderProduceRequest.builder().orderId("ORD-BTX-1").amount(new BigDecimal("33.00")).userId("u3").tenantId("tenant-tx-batch").build(),
                OrderProduceRequest.builder().orderId("ORD-BTX-2").amount(new BigDecimal("44.00")).userId("u4").tenantId("tenant-tx-batch").build()
        );
        BatchOrderProduceRequest txReq = BatchOrderProduceRequest.builder()
                .orders(new ValidList<>(txOrders))
                .build();

        mockMvc.perform(post("/sample/kafka/batch/orders-tx-commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(txReq))
                        .header("X-Trace-Id", "trace-batch-tx-e2e")
                        .header("X-Tenant-Id", "tenant-tx-batch")
                        .header("X-User-Id", "user-tx-batch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0]").value("ORD-BTX-1"))
                .andExpect(jsonPath("$.data[1]").value("ORD-BTX-2"));

        mockMvc.perform(get("/sample/kafka/produce/db-orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasItems("ORD-BTX-1", "ORD-BTX-2")));

        awaitCondition(() -> {
            mockMvc.perform(get("/sample/kafka/batch/received-logs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.order.orderId == 'ORD-BTX-1' && @.tenantId == 'tenant-tx-batch')]").exists())
                    .andExpect(jsonPath("$.data[?(@.order.orderId == 'ORD-BTX-2' && @.tenantId == 'tenant-tx-batch')]").exists());
        });

        TenantContextHolder.clear();
        UserContextHolder.clear();
        MDC.clear();

        // 3. 空集合安全返回空列表且零异常
        BatchOrderProduceRequest emptyReq = BatchOrderProduceRequest.builder()
                .orders(new ValidList<>(List.of()))
                .build();
        mockMvc.perform(post("/sample/kafka/batch/orders-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(emptyReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isEmpty());

        mockMvc.perform(post("/sample/kafka/batch/orders-tx-commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(emptyReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isEmpty());

        // 4. 对抗校验：sendBatchRecordsAsync 离线闭环与阶段 1 超限 Fail-Fast（零落库零发送）
        List<ProducerRecord<Object, Object>> batchRecords = List.of(
                new ProducerRecord<>(KafkaScenarioTopologyConfig.TOPIC_BATCH, "ORD-REC-1",
                        new OrderMessage("ORD-REC-1", new BigDecimal("55.00"), "u5", "tenant-rec"))
        );
        assertThat(kafkaReliableTemplate.sendBatchRecordsAsync(batchRecords).join()).hasSize(1);

        String oversizedOrderId = "X".repeat(1024 * 1024 + 128);
        List<OrderMessage> oversizedTxBatch = List.of(
                new OrderMessage("ORD-TX-ABORT-1", BigDecimal.TEN, "u6", "tenant-abort"),
                new OrderMessage(oversizedOrderId, BigDecimal.TEN, "u6", "tenant-abort")
        );
        assertThatThrownBy(() -> tradeScenarioService.createBatchOrdersWithSendAfterCommit(oversizedTxBatch))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超出最大限制");
        assertThat(tradeScenarioService.getLocalDbOrders()).doesNotContain("ORD-TX-ABORT-1");
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    // =========================================================================
    // 场景 11: 1MB 报文超限 Fail-Fast 拦截 (KafkaBatchController Web 闭环)
    // =========================================================================
    @Test
    @DisplayName("场景 11: 1MB 报文超限 Fail-Fast 拦截 (KafkaBatchController Web 闭环)")
    void testScenario11_PayloadTooLargeFailFast() throws Exception {
        // 1. HTTP 投递正常体积报文 (512B) -> 成功
        mockMvc.perform(post("/sample/kafka/batch/large-payload")
                        .param("sizeBytes", "512"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true));

        // 2. HTTP 投递超限大报文 (1MB + 64B) -> 前置 Fail-Fast 阻断，返回 400 业务错误响应
        mockMvc.perform(post("/sample/kafka/batch/large-payload")
                        .param("sizeBytes", String.valueOf(1048576 + 64)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("超出最大限制")));

        // 3. HTTP 原生分布式事务投递超限大报文 (1MB + 64B) -> 事务代理层 Fail-Fast 阻断，返回 400 业务错误响应
        mockMvc.perform(post("/sample/kafka/produce/order-native-tx-large-payload")
                        .param("sizeBytes", String.valueOf(1048576 + 64)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("超出最大限制")));

        // 4. 底层 Service 直接调用对齐断言（普通发送 + 原生分布式事务发送均触发 400 Fail-Fast）
        byte[] largePayload = new byte[1048576 + 64];

        assertThatThrownBy(() -> tradeScenarioService.sendLargePayload(largePayload))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getResultCode()).isEqualTo(CommonResultCode.BAD_REQUEST);
                    assertThat(be.getMessage()).contains("超出最大限制");
                });

        assertThatThrownBy(() -> tradeScenarioService.executeNativeTxLargePayload(largePayload))
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
    // 场景 13: 非阻塞阶梯延迟重试 (@RetryableTopic 阶梯主题流转与 4D 上下文透传, KafkaRetryController Web 闭环)
    // =========================================================================
    @Test
    @DisplayName("场景 13: 非阻塞阶梯延迟重试 (@RetryableTopic 阶梯主题流转与 4D 上下文透传, KafkaRetryController Web 闭环)")
    void testScenario13_NonBlockingTieredRetry() throws Exception {
        MDC.put("traceId", "trace-retry-999");
        TenantContextHolder.setTenantId("tenant-retry-888");
        UserContextHolder.setUserId("user-retry-777");

        // 1. HTTP 触发重试消息发送
        RetryTriggerRequest retryReq = RetryTriggerRequest.builder()
                .orderId("ORD-RETRY-001")
                .build();

        mockMvc.perform(post("/sample/kafka/retry/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(retryReq))
                        .param("fail", "false")
                        .header("X-Trace-Id", "trace-retry-999")
                        .header("X-Tenant-Id", "tenant-retry-888")
                        .header("X-User-Id", "user-retry-777"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true));

        // 1.1 对抗性校验：orderId 为空触发 JSR-303 Fail-Fast 阻断，返回 400
        RetryTriggerRequest invalidRetryReq = RetryTriggerRequest.builder()
                .orderId("")
                .build();
        mockMvc.perform(post("/sample/kafka/retry/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRetryReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));

        // 等待异步 @KafkaListener 完成首条 HTTP 触发消息消费
        awaitCondition(() -> assertThat(retryService.getAttemptCount()).isGreaterThanOrEqualTo(1));
        int baseAttemptCount = retryService.getAttemptCount();

        // 2. 发送正常消息 -> 验证同步消费与四维上下文透传
        MDC.put("traceId", "trace-retry-999");
        TenantContextHolder.setTenantId("tenant-retry-888");
        UserContextHolder.setUserId("user-retry-777");

        ConsumerRecord<String, String> normalRecord = new ConsumerRecord<>(
                NonBlockingRetryScenarioService.TOPIC_RETRY_MAIN, 0, 1L, "key-ok", "PAYLOAD_SUCCESS"
        );
        retryService.onMessage(normalRecord, NonBlockingRetryScenarioService.TOPIC_RETRY_MAIN);

        assertThat(retryService.getAttemptCount()).isEqualTo(baseAttemptCount + 1);
        NonBlockingRetryScenarioService.RetryRecordLog logEntry =
                retryService.getRetryLogs().get(retryService.getRetryLogs().size() - 1);
        assertThat(logEntry.traceId()).isEqualTo("trace-retry-999");
        assertThat(logEntry.tenantId()).isEqualTo("tenant-retry-888");
        assertThat(logEntry.userId()).isEqualTo("user-retry-777");

        // 3. 模拟失败 -> 触发异常与 DLT 终点拦截
        ConsumerRecord<String, String> failRecord = new ConsumerRecord<>(
                NonBlockingRetryScenarioService.TOPIC_RETRY_MAIN, 0, 2L, "key-fail", "PAYLOAD_FAIL"
        );
        assertThatThrownBy(() -> retryService.onMessage(failRecord, NonBlockingRetryScenarioService.TOPIC_RETRY_MAIN))
                .isInstanceOf(RuntimeException.class);

        // 4. 达到重试上限进入 DLT
        retryService.onDlt(failRecord);
        assertThat(retryService.getDltRecords()).contains("key-fail:PAYLOAD_FAIL");

        // 5. HTTP 探查重试流转执行日志
        mockMvc.perform(get("/sample/kafka/retry/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    }

    // =========================================================================
    // 场景 14: 运行期位移治理与实时 Lag 积压探测 (KafkaOffsetManager & KafkaOpsController Web 闭环)
    // =========================================================================
    @Test
    @DisplayName("场景 14: 运行期位移治理与实时 Lag 积压探测 (KafkaOffsetManager & KafkaOpsController Web 闭环)")
    void testScenario14_OffsetGovernanceAndRealtimeLagMonitoring() throws Exception {
        for (int i = 1; i <= 2; i++) {
            OrderMessage msg = new OrderMessage("ORD-LAG-" + i, new BigDecimal("50.00"), "u1", "t1");
            kafkaTemplate.send(KafkaScenarioTopologyConfig.TOPIC_ORDER, "lag-key-" + i, msg).get();
        }

        // 1. HTTP 回拨消费位移至 EARLIEST -> 产生可观测积压 Lag
        OffsetResetRequest earliestReq = OffsetResetRequest.builder()
                .groupId("sample-group")
                .topic(KafkaScenarioTopologyConfig.TOPIC_ORDER)
                .resetType(ResetType.EARLIEST)
                .build();

        mockMvc.perform(post("/sample/kafka/ops/reset-offsets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(earliestReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 2. HTTP 查询消费组当前提交位移（确认位点记录真实存在）
        mockMvc.perform(get("/sample/kafka/ops/committed-offsets")
                        .param("groupId", "sample-group"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isNotEmpty());

        // 3. HTTP 实时查询全分区与总积压 Lag（EARLIEST 下积压 >= 2）
        String earliestLagBody = mockMvc.perform(get("/sample/kafka/ops/lag")
                        .param("groupId", "sample-group")
                        .param("topic", KafkaScenarioTopologyConfig.TOPIC_ORDER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.topic").value(KafkaScenarioTopologyConfig.TOPIC_ORDER))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(objectMapper.readTree(earliestLagBody).path("data").path("totalLag").asLong())
                .isGreaterThanOrEqualTo(2L);

        // 4. HTTP 推进消费位移至 LATEST -> 积压归零
        OffsetResetRequest latestReq = OffsetResetRequest.builder()
                .groupId("sample-group")
                .topic(KafkaScenarioTopologyConfig.TOPIC_ORDER)
                .resetType(ResetType.LATEST)
                .build();

        mockMvc.perform(post("/sample/kafka/ops/reset-offsets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(latestReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        String latestLagBody = mockMvc.perform(get("/sample/kafka/ops/lag")
                        .param("groupId", "sample-group")
                        .param("topic", KafkaScenarioTopologyConfig.TOPIC_ORDER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(objectMapper.readTree(latestLagBody).path("data").path("totalLag").asLong())
                .isZero();

        // 5. HTTP 按时间戳回拨消费位移
        OffsetResetRequest timestampReq = OffsetResetRequest.builder()
                .groupId("sample-group")
                .topic(KafkaScenarioTopologyConfig.TOPIC_ORDER)
                .resetType(ResetType.TIMESTAMP)
                .timestampMs(System.currentTimeMillis())
                .build();

        mockMvc.perform(post("/sample/kafka/ops/reset-offsets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(timestampReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 5.1 对抗性校验：重设策略为 TIMESTAMP 时 timestampMs 缺失触发 Fail-Fast 阻断，返回 400
        OffsetResetRequest invalidTimestampReq = OffsetResetRequest.builder()
                .groupId("sample-group")
                .topic(KafkaScenarioTopologyConfig.TOPIC_ORDER)
                .resetType(ResetType.TIMESTAMP)
                .timestampMs(null)
                .build();

        mockMvc.perform(post("/sample/kafka/ops/reset-offsets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidTimestampReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("timestampMs 必须为有效正数时间戳")));

        // 6. 底层 Service 直接调用对齐断言
        ConsumerGroupLag lag = opsService.getConsumerGroupLag("sample-group", KafkaScenarioTopologyConfig.TOPIC_ORDER);
        assertThat(lag).isNotNull();
        assertThat(lag.groupId()).isEqualTo("sample-group");
        assertThat(lag.topic()).isEqualTo(KafkaScenarioTopologyConfig.TOPIC_ORDER);
        assertThat(lag.totalLag()).isGreaterThanOrEqualTo(0L);
        assertThat(lag.partitionLags()).allSatisfy(p -> {
            assertThat(p.logEndOffset()).isGreaterThanOrEqualTo(Math.max(0L, p.committedOffset()));
            assertThat(p.lag()).isEqualTo(Math.max(0L, p.logEndOffset() - Math.max(0L, p.committedOffset())));
        });
    }

    // =========================================================================
    // 场景 15: 运行期消费容器动态流控 (KafkaContainerManager & KafkaOpsController Web 闭环)
    // =========================================================================
    @Test
    @DisplayName("场景 15: 运行期消费容器动态流控 (KafkaContainerManager & KafkaOpsController Web 闭环)")
    void testScenario15_ConsumerContainerDynamicFlowControl() throws Exception {
        // 1. HTTP 枚举全部监听容器快照，验证真实注册列表非空
        mockMvc.perform(get("/sample/kafka/ops/containers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()", org.hamcrest.Matchers.greaterThanOrEqualTo(4)));

        // 2. HTTP 暂停指定 Topic 消费容器
        ContainerActionRequest pauseReq = ContainerActionRequest.builder()
                .containerIdOrTopic(KafkaScenarioTopologyConfig.TOPIC_ORDER)
                .action(ContainerAction.PAUSE)
                .build();

        mockMvc.perform(post("/sample/kafka/ops/container-action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pauseReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 3. HTTP 查询升级后的 KafkaContainerStatus 运行与暂停详情视图
        mockMvc.perform(get("/sample/kafka/ops/container-status")
                        .param("idOrTopic", KafkaScenarioTopologyConfig.TOPIC_ORDER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.paused").value(true))
                .andExpect(jsonPath("$.data.running").value(true))
                .andExpect(jsonPath("$.data.batchListener").value(false))
                .andExpect(jsonPath("$.data.concurrency").value(3))
                .andExpect(jsonPath("$.data.activeContainerCount").value(3))
                .andExpect(jsonPath("$.data.topics", org.hamcrest.Matchers.hasItem(KafkaScenarioTopologyConfig.TOPIC_ORDER)))
                .andExpect(jsonPath("$.data.containerType").value("ConcurrentMessageListenerContainer"));

        mockMvc.perform(get("/sample/kafka/ops/container-status")
                        .param("idOrTopic", KafkaScenarioTopologyConfig.TOPIC_BATCH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.batchListener").value(true));

        // 4. HTTP 恢复指定 Topic 消费容器并验证动态并发扩容生效
        ContainerActionRequest resumeReq = ContainerActionRequest.builder()
                .containerIdOrTopic(KafkaScenarioTopologyConfig.TOPIC_ORDER)
                .action(ContainerAction.RESUME)
                .build();

        mockMvc.perform(post("/sample/kafka/ops/container-action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resumeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        ContainerScaleRequest scaleReq = ContainerScaleRequest.builder()
                .containerIdOrTopic(KafkaScenarioTopologyConfig.TOPIC_ORDER)
                .concurrency(2)
                .build();

        mockMvc.perform(post("/sample/kafka/ops/container-scale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(scaleReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(get("/sample/kafka/ops/container-status")
                        .param("idOrTopic", KafkaScenarioTopologyConfig.TOPIC_ORDER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.running").value(true))
                .andExpect(jsonPath("$.data.concurrency").value(2))
                .andExpect(jsonPath("$.data.activeContainerCount").value(2));

        // 4.1 校验未匹配目标与非法并发数防卫
        ContainerScaleRequest missReq = ContainerScaleRequest.builder()
                .containerIdOrTopic("nonexistent.scale.topic")
                .concurrency(2)
                .build();
        mockMvc.perform(post("/sample/kafka/ops/container-scale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(missReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(false));

        ContainerScaleRequest invalidReq = ContainerScaleRequest.builder()
                .containerIdOrTopic(KafkaScenarioTopologyConfig.TOPIC_ORDER)
                .concurrency(0)
                .build();
        mockMvc.perform(post("/sample/kafka/ops/container-scale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(get("/sample/kafka/ops/container-status")
                        .param("idOrTopic", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));

        // 5. HTTP 启动与停止容器生命周期管控
        ContainerActionRequest startReq = ContainerActionRequest.builder()
                .containerIdOrTopic(KafkaScenarioTopologyConfig.TOPIC_ORDER)
                .action(ContainerAction.START)
                .build();

        mockMvc.perform(post("/sample/kafka/ops/container-action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        ContainerActionRequest stopReq = ContainerActionRequest.builder()
                .containerIdOrTopic(KafkaScenarioTopologyConfig.TOPIC_ORDER)
                .action(ContainerAction.STOP)
                .build();

        mockMvc.perform(post("/sample/kafka/ops/container-action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(stopReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
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
