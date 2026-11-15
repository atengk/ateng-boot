package io.github.atengk.boot.sample.rocketmq;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.MessageDeliveryException;
import io.github.atengk.boot.rocketmq.checker.RocketmqTransactionChecker;
import io.github.atengk.boot.rocketmq.constant.RocketmqConstants;
import io.github.atengk.boot.rocketmq.consumer.PushConsumerFactory;
import io.github.atengk.boot.rocketmq.consumer.SimpleConsumerFactory;
import io.github.atengk.boot.rocketmq.listener.RocketmqConsumerContainer;
import io.github.atengk.boot.rocketmq.listener.RocketmqListenerInvoker;
import io.github.atengk.boot.rocketmq.model.DeadLetterReplayResult;
import io.github.atengk.boot.rocketmq.model.RocketmqDeadLetterRecord;
import io.github.atengk.boot.rocketmq.spi.RocketmqTransactionFailureCallback;
import io.github.atengk.boot.rocketmq.template.RocketmqTemplate;
import io.github.atengk.boot.sample.rocketmq.checker.SampleOrderTransactionChecker;
import io.github.atengk.boot.sample.rocketmq.consumer.*;
import io.github.atengk.boot.sample.rocketmq.dto.DeadLetterPoisonPillRequest;
import io.github.atengk.boot.sample.rocketmq.dto.OrderProduceResponse;
import io.github.atengk.boot.sample.rocketmq.dto.SimpleConsumeReportVO;
import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import io.github.atengk.boot.sample.rocketmq.producer.TradeRocketmqProducer;
import io.github.atengk.boot.sample.rocketmq.service.*;
import io.github.atengk.boot.sample.rocketmq.testdouble.InMemoryRocketmqBroker;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.apache.rocketmq.client.apis.producer.TransactionResolution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RocketMQ 场景沙箱 1:1 端到端生产级集成测试套件
 *
 * <p>全面覆盖 20 大核心端到端生产场景：
 * <ul>
 *   <li>场景 1：同步发信、强类型推断反序列化与四维上下文透传 (MDC/Tenant/User)；</li>
 *   <li>场景 2：异步发信与非阻塞回调 (CompletableFuture)；</li>
 *   <li>场景 3：单向发信埋点极速投递 (sendOneway)；</li>
 *   <li>场景 4：Tag 逻辑或多分支匹配过滤 (create || pay)；</li>
 *   <li>场景 5：SQL92 服务端属性过滤与计算下推 (amount > 100)；</li>
 *   <li>场景 6：FIFO 顺序局部保序消息 (MessageGroup)；</li>
 *   <li>场景 7：毫秒级延时消息投递 (sendDelayed)；</li>
 *   <li>场景 8：精准绝对时间定时消息调度 (sendDeliverAt)；</li>
 *   <li>场景 9：定时延时消息原生精准撤回 (recallMessage)；</li>
 *   <li>场景 10：批量可靠发信与微切片并发汇聚 (sendBatch / sendBatchAsync)；</li>
 *   <li>场景 11：原生 2PC 分布式事务半消息提交 (sendTransaction -> COMMIT)；</li>
 *   <li>场景 12：原生 2PC 分布式事务半消息回滚丢弃 (sendTransaction -> ROLLBACK)；</li>
 *   <li>场景 13：统一事务回查分发架构 (RocketmqTransactionChecker)；</li>
 *   <li>场景 14：本地事务后置感知发信 (sendAfterCommit -> COMMIT)；</li>
 *   <li>场景 15：本地事务异常回滚防幽灵消息 (sendAfterCommit -> ROLLBACK)；</li>
 *   <li>场景 16：4MB 大报文防御超限 Fail-Fast 拦截；</li>
 *   <li>场景 17：毒丸不可自愈异常安全丢弃与快速确认；</li>
 *   <li>场景 18：@Idempotent 防重冲突静默确认阻断重试；</li>
 *   <li>场景 19：SimpleConsumer 流式微批拉取、隐身租约续期与显式 ACK 确认；</li>
 *   <li>场景 20：运行期容器运维管控 (pause/resume) 与死信队列治理 (peek/replay/防二次雪崩)。</li>
 * </ul>
 * 100% 内存自洽运行，零外部网络与 Docker 依赖，秒级全绿。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@SpringBootTest(classes = {SampleRocketmqApplication.class, SampleRocketmqE2eTest.TestRocketmqConfig.class})
@DisplayName("RocketMQ 场景沙箱 1:1 端到端生产级集成测试 (20 大核心场景全覆盖)")
class SampleRocketmqE2eTest {

    @Autowired
    private StandardRocketmqScenarioService standardScenarioService;

    @Autowired
    private TransactionOrderScenarioService transactionScenarioService;

    @Autowired
    private BatchDelayScenarioService batchDelayScenarioService;

    @Autowired
    private SimpleConsumerPullScenarioService simpleConsumerPullScenarioService;

    @Autowired
    private DeadLetterReplayScenarioService deadLetterReplayScenarioService;

    @Autowired
    private ContainerOpsScenarioService containerOpsScenarioService;

    @Autowired
    private RocketmqTemplate rocketmqTemplate;

    @Autowired
    private TradeRocketmqProducer producer;

    @Autowired
    private OrderStandardConsumer standardConsumer;

    @Autowired
    private OrderDelayConsumer delayConsumer;

    @Autowired
    private OrderFifoConsumer fifoConsumer;

    @Autowired
    private OrderTransactionConsumer txConsumer;

    @Autowired
    private OrderSqlConsumer sqlConsumer;

    @Autowired
    private IdempotentRocketmqConsumer idempotentConsumer;

    @Autowired
    private PoisonPillRocketmqConsumer poisonConsumer;

    @Autowired
    private SampleOrderTransactionChecker sampleOrderTransactionChecker;

    @Autowired
    private RocketmqTransactionChecker globalTransactionChecker;

    @Autowired
    private RocketmqConsumerContainer consumerContainer;

    @Autowired(required = false)
    private InMemoryRocketmqBroker testBroker;

    @Autowired(required = false)
    private RecordingTransactionFailureCallback recordingFailureCallback;

    @Autowired(required = false)
    private SamplePoisonPillCallbackRecorder poisonPillCallbackRecorder;

    @BeforeEach
    void setUp() {
        MDC.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();

        standardConsumer.clear();
        delayConsumer.clear();
        fifoConsumer.clear();
        txConsumer.clear();
        sqlConsumer.clear();
        idempotentConsumer.clear();
        poisonConsumer.clear();
        sampleOrderTransactionChecker.clear();
        transactionScenarioService.clear();
        if (testBroker != null) {
            testBroker.clear();
        }
        if (recordingFailureCallback != null) {
            recordingFailureCallback.clear();
        }
        if (poisonPillCallbackRecorder != null) {
            poisonPillCallbackRecorder.clear();
        }
    }

    private void awaitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(UserContextHolder.getUserId()).isNull();
        if (poisonPillCallbackRecorder != null) {
            poisonPillCallbackRecorder.clear();
        }
    }

    // =========================================================================
    // 场景 1：同步发信与四维上下文透传
    // =========================================================================

    @Test
    @DisplayName("场景 1：同步发信 -> 四维上下文透传无损还原 -> 强类型反序列化消费")
    void testSyncSendAndContextPropagation() {
        MDC.put("traceId", "TRACE-SYNC-8888");
        TenantContextHolder.setTenantId("TENANT-EAST-01");
        UserContextHolder.setUserId("USER-ATENG-99");

        OrderMessage order = OrderMessage.builder()
                .orderId("ORD-SYNC-001")
                .amount(299.9)
                .customerName("张三")
                .status("CREATED")
                .build();

        SendReceipt receipt = standardScenarioService.sendSync(order);

        assertThat(receipt).isNotNull();
        assertThat(receipt.getMessageId()).isNotNull();

        awaitUntil(() -> !standardConsumer.getReceivedOrders().isEmpty());
        assertThat(standardConsumer.getReceivedOrders()).hasSize(1);
        OrderMessage received = standardConsumer.getReceivedOrders().get(0);
        assertThat(received.getOrderId()).isEqualTo("ORD-SYNC-001");
        assertThat(received.getAmount()).isEqualTo(299.9);
        assertThat(received.getCustomerName()).isEqualTo("张三");

        assertThat(standardConsumer.getReceivedTraceIds()).contains("TRACE-SYNC-8888");
        assertThat(standardConsumer.getReceivedTenantIds()).contains("TENANT-EAST-01");
    }

    // =========================================================================
    // 场景 2：异步发信与非阻塞回调
    // =========================================================================

    @Test
    @DisplayName("场景 2：异步发信 -> CompletableFuture 回调 -> 顺利消费")
    void testAsyncSendAndConsume() {
        MDC.put("traceId", "TRACE-ASYNC-7777");
        TenantContextHolder.setTenantId("TENANT-WEST-02");

        OrderMessage order = OrderMessage.builder()
                .orderId("ORD-ASYNC-002")
                .amount(599.0)
                .customerName("李四")
                .status("CREATED")
                .build();

        CompletableFuture<SendReceipt> future = standardScenarioService.sendAsync(order);
        SendReceipt receipt = future.join();

        assertThat(receipt).isNotNull();
        awaitUntil(() -> !standardConsumer.getReceivedOrders().isEmpty());
        assertThat(standardConsumer.getReceivedOrders()).hasSize(1);
        assertThat(standardConsumer.getReceivedOrders().get(0).getOrderId()).isEqualTo("ORD-ASYNC-002");
    }

    // =========================================================================
    // 场景 3：单向发信埋点极速投递 (sendOneway)
    // =========================================================================

    @Test
    @DisplayName("场景 3：单向发信 (sendOneway) 极速发射埋点日志报文")
    void testOnewaySend() {
        OrderMessage order = OrderMessage.builder()
                .orderId("ORD-ONEWAY-003")
                .amount(10.0)
                .customerName("日志监控")
                .status("TRACK")
                .build();

        standardScenarioService.sendOneway(order);

        if (testBroker != null) {
            assertThat(testBroker.getSentMessageCount().get()).isGreaterThanOrEqualTo(1);
        }
    }

    // =========================================================================
    // 场景 4：Tag 逻辑或多分支匹配过滤 (create || pay)
    // =========================================================================

    @Test
    @DisplayName("场景 4：Tag 逻辑或多分支过滤 (create || pay) -> 验证 pay 标签消费与未匹配标签安全过滤")
    void testTagLogicalOrFiltering() {
        OrderMessage payOrder = OrderMessage.builder()
                .orderId("ORD-TAG-PAY-001")
                .amount(888.0)
                .customerName("王五")
                .status("PAID")
                .build();

        standardScenarioService.sendSync(payOrder, "pay");

        awaitUntil(() -> !standardConsumer.getReceivedOrders().isEmpty());
        assertThat(standardConsumer.getReceivedOrders()).hasSize(1);
        assertThat(standardConsumer.getReceivedOrders().get(0).getOrderId()).isEqualTo("ORD-TAG-PAY-001");

        OrderMessage cancelOrder = OrderMessage.builder()
                .orderId("ORD-TAG-CANCEL-001")
                .amount(888.0)
                .customerName("王五")
                .status("CANCELLED")
                .build();

        standardScenarioService.sendSync(cancelOrder, "cancel");

        // 仅消费了 1 条 pay 订单，未匹配的 cancel 标签被安全过滤丢弃
        assertThat(standardConsumer.getReceivedOrders()).hasSize(1);
    }

    // =========================================================================
    // 场景 5：SQL92 服务端属性过滤与计算下推
    // =========================================================================

    @Test
    @DisplayName("场景 5：SQL92 服务端属性过滤 (amount > 100) -> 验证高金额消费与低金额过滤")
    void testSql92Filtering() {
        OrderMessage highOrder = OrderMessage.builder()
                .orderId("ORD-SQL-HIGH-001")
                .amount(200.0)
                .customerName("大额客户")
                .status("CREATED")
                .build();
        standardScenarioService.sendSqlFiltered(highOrder, 200.0);

        awaitUntil(() -> !sqlConsumer.getReceivedSqlOrders().isEmpty());
        assertThat(sqlConsumer.getReceivedSqlOrders()).hasSize(1);
        assertThat(sqlConsumer.getReceivedSqlOrders().get(0).getOrderId()).isEqualTo("ORD-SQL-HIGH-001");

        OrderMessage lowOrder = OrderMessage.builder()
                .orderId("ORD-SQL-LOW-001")
                .amount(50.0)
                .customerName("小额客户")
                .status("CREATED")
                .build();
        standardScenarioService.sendSqlFiltered(lowOrder, 50.0);

        // 依然只有 1 条高额订单，小额订单被 SQL92 表达式过滤
        assertThat(sqlConsumer.getReceivedSqlOrders()).hasSize(1);
    }

    // =========================================================================
    // 场景 6：FIFO 顺序局部保序消息 (MessageGroup)
    // =========================================================================

    @Test
    @DisplayName("场景 6：FIFO 顺序保序消息 (MessageGroup) 局部有序投递与消费")
    void testFifoMessageGroup() {
        String shardGroup = "SHARD_USER_1001";

        OrderMessage step1 = OrderMessage.builder().orderId("ORD-FIFO-1").status("STEP_1").build();
        OrderMessage step2 = OrderMessage.builder().orderId("ORD-FIFO-2").status("STEP_2").build();
        OrderMessage step3 = OrderMessage.builder().orderId("ORD-FIFO-3").status("STEP_3").build();

        standardScenarioService.sendFifo(step1, shardGroup);
        standardScenarioService.sendFifo(step2, shardGroup);
        standardScenarioService.sendFifo(step3, shardGroup);

        awaitUntil(() -> fifoConsumer.getReceivedFifoOrders().size() >= 3);
        assertThat(fifoConsumer.getReceivedFifoOrders()).hasSize(3);
        assertThat(fifoConsumer.getReceivedFifoOrders().get(0).getOrderId()).isEqualTo("ORD-FIFO-1");
        assertThat(fifoConsumer.getReceivedFifoOrders().get(1).getOrderId()).isEqualTo("ORD-FIFO-2");
        assertThat(fifoConsumer.getReceivedFifoOrders().get(2).getOrderId()).isEqualTo("ORD-FIFO-3");
    }

    // =========================================================================
    // 场景 7：毫秒级延时消息投递 (sendDelayed)
    // =========================================================================

    @Test
    @DisplayName("场景 7：毫秒级延时消息投递 (Duration.ofMillis)")
    void testDelayedMessage() {
        OrderMessage delayOrder = OrderMessage.builder()
                .orderId("ORD-DELAY-001")
                .amount(120.0)
                .customerName("延时买家")
                .status("UNPAID")
                .build();

        SendReceipt receipt = batchDelayScenarioService.sendDelayed(delayOrder, Duration.ofMillis(500));

        assertThat(receipt).isNotNull();
        awaitUntil(() -> !delayConsumer.getReceivedDelayOrders().isEmpty());
        assertThat(delayConsumer.getReceivedDelayOrders()).hasSize(1);
        assertThat(delayConsumer.getReceivedDelayOrders().get(0).getOrderId()).isEqualTo("ORD-DELAY-001");
    }

    // =========================================================================
    // 场景 8：精准绝对时间定时消息调度 (sendDeliverAt)
    // =========================================================================

    @Test
    @DisplayName("场景 8：精准绝对时间定时消息调度 (Instant)")
    void testDeliverAtMessage() {
        OrderMessage scheduledOrder = OrderMessage.builder()
                .orderId("ORD-SCHEDULE-001")
                .amount(300.0)
                .customerName("定时买家")
                .status("SCHEDULED")
                .build();

        Instant deliverAt = Instant.now().plusMillis(500);
        SendReceipt receipt = batchDelayScenarioService.sendDeliverAt(scheduledOrder, deliverAt);

        assertThat(receipt).isNotNull();
        awaitUntil(() -> !delayConsumer.getReceivedDelayOrders().isEmpty());
        assertThat(delayConsumer.getReceivedDelayOrders()).hasSize(1);
        assertThat(delayConsumer.getReceivedDelayOrders().get(0).getOrderId()).isEqualTo("ORD-SCHEDULE-001");
    }

    // =========================================================================
    // 场景 9：定时延时消息原生精准撤回 (recallMessage)
    // =========================================================================

    @Test
    @DisplayName("场景 9：定时延时消息原生精准撤回 (recallMessage)")
    void testRecallDelayedMessage() {
        OrderMessage order = OrderMessage.builder()
                .orderId("ORD-RECALL-001")
                .amount(99.0)
                .status("PENDING_PAY")
                .build();

        SendReceipt receipt = batchDelayScenarioService.sendDelayed(order, Duration.ofMinutes(30));
        assertThat(receipt).isNotNull();
        String recallHandle = receipt.getRecallHandle();
        if (recallHandle != null && !recallHandle.isBlank()) {
            batchDelayScenarioService.recallDelayedMessage(TradeRocketmqProducer.TOPIC_DELAY, recallHandle);
            if (testBroker != null) {
                assertThat(testBroker.getRecalledHandles()).contains(recallHandle);
            }
        }
    }

    // =========================================================================
    // 场景 10：批量可靠发信与微切片并发汇聚 (sendBatch)
    // =========================================================================

    @Test
    @DisplayName("场景 10：批量并发发信与微切片并发汇聚 (sendBatch / sendBatchAsync + userProperties)")
    void testBatchSend() {
        List<OrderMessage> batchOrders = List.of(
                OrderMessage.builder().orderId("ORD-BATCH-1").amount(10.0).customerName("批买家1").status("CREATED").build(),
                OrderMessage.builder().orderId("ORD-BATCH-2").amount(20.0).customerName("批买家2").status("CREATED").build(),
                OrderMessage.builder().orderId("ORD-BATCH-3").amount(30.0).customerName("批买家3").status("CREATED").build()
        );

        List<SendReceipt> receipts = batchDelayScenarioService.sendBatch(batchOrders);
        assertThat(receipts).hasSize(3);

        CompletableFuture<List<SendReceipt>> asyncFuture = batchDelayScenarioService.sendBatchAsync(
                batchOrders, Map.of("region", "cn-east", "batchTag", "e2e"));
        List<SendReceipt> asyncReceipts = asyncFuture.join();
        assertThat(asyncReceipts).hasSize(3);

        // 验证空集保底返回空不可变集合，超 64 条 (130条) 自动分片发送并完整保序返回
        List<SendReceipt> emptyReceipts = batchDelayScenarioService.sendBatch(List.of());
        assertThat(emptyReceipts).isEmpty();

        List<OrderMessage> slicedOrders = new ArrayList<>(130);
        for (int i = 0; i < 130; i++) {
            slicedOrders.add(OrderMessage.builder()
                    .orderId("ORD-E2E-SLICE-" + i)
                    .amount(100.0 + i)
                    .customerName("切片买家" + i)
                    .status("CREATED")
                    .build());
        }
        List<SendReceipt> slicedReceipts = batchDelayScenarioService.sendBatch(slicedOrders, Map.of("sliceTest", "true"));
        assertThat(slicedReceipts).hasSize(130);

        if (testBroker != null) {
            List<MessageView> orderMessages = testBroker.getSentMessages(TradeRocketmqProducer.TOPIC_ORDER);
            assertThat(orderMessages).anySatisfy(mv ->
                    assertThat(mv.getProperties()).containsEntry("region", "cn-east").containsEntry("batchTag", "e2e"));
            assertThat(orderMessages).anySatisfy(mv ->
                    assertThat(mv.getProperties()).containsEntry("sliceTest", "true"));
        }
    }

    // =========================================================================
    // 场景 11：原生 2PC 分布式事务半消息提交 (COMMIT)
    // =========================================================================

    @Test
    @DisplayName("场景 11：原生 2PC 分布式事务半消息 -> 本地事务决断 COMMIT -> 分发投递成功")
    void test2PcTransactionCommit() {
        OrderMessage txOrder = OrderMessage.builder()
                .orderId("ORD-TX-COMMIT-001")
                .amount(1999.0)
                .customerName("事务买家")
                .status("PENDING")
                .build();

        SendReceipt receipt = transactionScenarioService.createOrderWith2PcTransaction(txOrder, true);

        assertThat(receipt).isNotNull();
        assertThat(transactionScenarioService.getLocalDbOrders()).contains("ORD-TX-COMMIT-001");
        assertThat(sampleOrderTransactionChecker.isCommitted("ORD-TX-COMMIT-001")).isTrue();

        awaitUntil(() -> !txConsumer.getReceivedTxOrders().isEmpty());
        assertThat(txConsumer.getReceivedTxOrders()).hasSize(1);
        assertThat(txConsumer.getReceivedTxOrders().get(0).getOrderId()).isEqualTo("ORD-TX-COMMIT-001");
    }

    // =========================================================================
    // 场景 12：原生 2PC 分布式事务半消息回滚丢弃 (ROLLBACK)
    // =========================================================================

    @Test
    @DisplayName("场景 12：原生 2PC 分布式事务半消息 -> 本地事务决断 ROLLBACK -> 半消息丢弃不分发")
    void test2PcTransactionRollback() {
        OrderMessage txOrder = OrderMessage.builder()
                .orderId("ORD-TX-ROLLBACK-002")
                .amount(2999.0)
                .customerName("回滚买家")
                .status("PENDING")
                .build();

        SendReceipt receipt = transactionScenarioService.createOrderWith2PcTransaction(txOrder, false);

        assertThat(receipt).isNotNull();
        assertThat(sampleOrderTransactionChecker.isRolledBack("ORD-TX-ROLLBACK-002")).isTrue();

        // 事务回滚，半消息被丢弃，消费端绝不会收到该订单
        assertThat(txConsumer.getReceivedTxOrders()).isEmpty();
    }

    // =========================================================================
    // 场景 13：统一事务回查分发架构 (RocketmqTransactionChecker)
    // =========================================================================

    @Test
    @DisplayName("场景 13：全局统一事务回查分发器 (RocketmqTransactionChecker) 准确路由匹配主题")
    void testTransactionCheckerDistribution() {
        sampleOrderTransactionChecker.markCommitted("ORD-CHECK-888");

        MessageView mockView = InMemoryRocketmqBroker.createMockMessageView(
                TradeRocketmqProducer.TOPIC_TX, "TX-MSG-888", new byte[0], Map.of("X-Biz-Key", "ORD-CHECK-888"), "tx", null);

        TransactionResolution resolution = globalTransactionChecker.check(mockView);
        assertThat(resolution).isEqualTo(TransactionResolution.COMMIT);
    }

    // =========================================================================
    // 场景 14：本地事务后置感知发信 (sendAfterCommit -> COMMIT)
    // =========================================================================

    @Test
    @DisplayName("场景 14：本地事务提交后发信 (sendAfterCommit) 正常提交与顺利投递")
    void testSendAfterCommitSuccess() {
        OrderMessage order = OrderMessage.builder()
                .orderId("ORD-COMMIT-001")
                .amount(150.0)
                .customerName("后置提交买家")
                .status("CREATED")
                .build();

        transactionScenarioService.createOrderWithSendAfterCommit(order);

        assertThat(transactionScenarioService.getLocalDbOrders()).contains("ORD-COMMIT-001");
        awaitUntil(() -> !standardConsumer.getReceivedOrders().isEmpty());
        assertThat(standardConsumer.getReceivedOrders()).hasSize(1);
        assertThat(standardConsumer.getReceivedOrders().get(0).getOrderId()).isEqualTo("ORD-COMMIT-001");
    }

    // =========================================================================
    // 场景 15：本地事务异常回滚防幽灵消息 (sendAfterCommit -> ROLLBACK)
    // =========================================================================

    @Test
    @DisplayName("场景 15：本地事务异常回滚防幽灵消息 -> sendAfterCommit 挂载消息自动丢弃")
    void testSendAfterCommitRollback() {
        OrderMessage order = OrderMessage.builder()
                .orderId("ORD-ROLLBACK-002")
                .amount(250.0)
                .customerName("回滚幽灵买家")
                .status("CREATED")
                .build();

        assertThatThrownBy(() -> transactionScenarioService.createOrderWithRollback(order))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("模拟业务校验失败，触发本地事务回滚");

        // 验证数据库模拟记录存在，但消息由于事务回滚被绝对丢弃，未投递至 Broker
        assertThat(transactionScenarioService.getLocalDbOrders()).contains("ORD-ROLLBACK-002");
        assertThat(standardConsumer.getReceivedOrders()).isEmpty();
    }

    @Test
    @DisplayName("场景 15b：H2 本地事务批量提交与回滚 (sendBatchAfterCommit -> COMMIT / ROLLBACK / 部分失败 6 参补偿隔离)")
    void testSendBatchAfterCommitCommitRollbackAndPartialFailure() {
        // 1. H2 本地事务批量提交 + 四维上下文捕获与 SQL92 属性透传
        MDC.put("traceId", "TRACE-BATCH-TX-001");
        TenantContextHolder.setTenantId("TENANT-BATCH-TX");
        UserContextHolder.setUserId("USER-BATCH-TX");
        TenantContextHolder.setIgnoreTenant(true);

        List<OrderMessage> commitOrders = List.of(
                OrderMessage.builder().orderId("ORD-BTX-OK-1").amount(110.0).customerName("批tx1").status("CREATED").build(),
                OrderMessage.builder().orderId("ORD-BTX-OK-2").amount(220.0).customerName("批tx2").status("CREATED").build()
        );
        transactionScenarioService.createOrdersWithSendBatchAfterCommit(
                commitOrders, "create", Map.of("batchChannel", "tx-h2"));

        awaitUntil(() -> standardConsumer.getReceivedOrders().size() >= 2);
        assertThat(standardConsumer.getReceivedOrders())
                .extracting(OrderMessage::getOrderId)
                .containsExactly("ORD-BTX-OK-1", "ORD-BTX-OK-2");
        assertThat(standardConsumer.getReceivedTraceIds()).contains("TRACE-BATCH-TX-001");
        assertThat(standardConsumer.getReceivedTenantIds()).contains("TENANT-BATCH-TX");
        if (testBroker != null) {
            assertThat(testBroker.getSentMessages(TradeRocketmqProducer.TOPIC_ORDER)).allSatisfy(mv ->
                    assertThat(mv.getProperties())
                            .containsEntry("batchChannel", "tx-h2")
                            .containsEntry("X-User-Id", "USER-BATCH-TX")
                            .containsEntry("X-Ignore-Tenant", "true"));
        }

        // 2. H2 本地事务批量回滚 -> 零消息投递
        standardConsumer.clear();
        if (testBroker != null) {
            testBroker.clear();
        }
        List<OrderMessage> rollbackOrders = List.of(
                OrderMessage.builder().orderId("ORD-BTX-RB-1").amount(310.0).customerName("批回滚1").status("CREATED").build(),
                OrderMessage.builder().orderId("ORD-BTX-RB-2").amount(320.0).customerName("批回滚2").status("CREATED").build()
        );
        assertThatThrownBy(() -> transactionScenarioService.createOrdersWithBatchRollback(
                rollbackOrders, "create", Map.of("batchChannel", "tx-rb")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("触发本地事务回滚");
        assertThat(standardConsumer.getReceivedOrders()).isEmpty();
        if (testBroker != null) {
            assertThat(testBroker.getSentMessages(TradeRocketmqProducer.TOPIC_ORDER)).isEmpty();
        }

        // 3. H2 本地事务提交后部分单条发信失败 -> 触发 6 参 SPI 补偿且不阻断同批正常消息投递
        if (testBroker != null && recordingFailureCallback != null) {
            standardConsumer.clear();
            testBroker.clear();
            recordingFailureCallback.clear();

            MDC.put("traceId", "TRACE-BATCH-PARTIAL-002");
            testBroker.failSendContaining("ORD-BTX-PARTIAL-FAIL-2");
            List<OrderMessage> partialOrders = List.of(
                    OrderMessage.builder().orderId("ORD-BTX-PARTIAL-1").amount(10.0).customerName("部分1").status("CREATED").build(),
                    OrderMessage.builder().orderId("ORD-BTX-PARTIAL-FAIL-2").amount(20.0).customerName("部分失败2").status("CREATED").build(),
                    OrderMessage.builder().orderId("ORD-BTX-PARTIAL-3").amount(30.0).customerName("部分3").status("CREATED").build()
            );
            transactionScenarioService.createOrdersWithSendBatchAfterCommit(partialOrders, "create", null);

            awaitUntil(() -> standardConsumer.getReceivedOrders().size() >= 2);
            assertThat(standardConsumer.getReceivedOrders())
                    .extracting(OrderMessage::getOrderId)
                    .containsExactly("ORD-BTX-PARTIAL-1", "ORD-BTX-PARTIAL-3");
            assertThat(recordingFailureCallback.getRecords()).hasSize(1);
            FailureRecord failure = recordingFailureCallback.getRecords().get(0);
            assertThat(failure.topic()).isEqualTo(TradeRocketmqProducer.TOPIC_ORDER);
            assertThat(failure.tag()).isEqualTo("create");
            assertThat(failure.traceId()).isEqualTo("TRACE-BATCH-PARTIAL-002");
            assertThat(failure.payload()).isInstanceOf(OrderMessage.class);
            assertThat(((OrderMessage) failure.payload()).getOrderId()).isEqualTo("ORD-BTX-PARTIAL-FAIL-2");
        }
    }

    // =========================================================================
    // 场景 16：4MB 大报文防御超限 Fail-Fast 拦截
    // =========================================================================

    @Test
    @DisplayName("场景 16：4MB 大报文防御超限 Fail-Fast 拦截")
    void testMaxPayloadSizeDefense() {
        byte[] largeBytes = new byte[5 * 1024 * 1024]; // 5MB

        // 正常发送小报文不抛异常
        assertThat(producer.sendOrderWithTag(OrderMessage.builder().orderId("ORD-NORMAL").build(), "create")).isNotNull();

        // 通过底层发送超限载荷 (5MB > 4MB 上限)，触发 Fail-Fast
        assertThatThrownBy(() -> producer.getRocketmqTemplate().send(TradeRocketmqProducer.TOPIC_ORDER, largeBytes))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("消息体过大");
    }

    // =========================================================================
    // 场景 17：毒丸不可自愈异常安全丢弃与快速确认
    // =========================================================================

    @Test
    @DisplayName("场景 17：毒丸不可自愈异常 (JSR-303 参数校验失败) 安全丢弃与静默确认")
    void testPoisonPillSafeDiscard() {
        OrderMessage invalidOrder = OrderMessage.builder()
                .orderId("POISON_INVALID_AMOUNT")
                .amount(-99.0) // 负数金额，触发 @Positive 校验失败
                .customerName("毒丸客户")
                .status("POISON")
                .build();

        standardScenarioService.sendSync(invalidOrder, "poison");

        // 毒丸报文在 invoker 处被 JSR-303 快速失败拦截并丢弃，消费者方法未被调用
        assertThat(poisonConsumer.getPoisonProcessCount().get()).isEqualTo(0);
    }

    // =========================================================================
    // 场景 18：@Idempotent 防重冲突静默确认阻断重试
    // =========================================================================

    @Test
    @DisplayName("场景 18：防重冲突 (IdempotentException) 静默确认阻断无限重试")
    void testIdempotentSilentAck() {
        OrderMessage order = OrderMessage.builder()
                .orderId("ORD-IDEMPOTENT-001")
                .amount(100.0)
                .customerName("幂等买家")
                .status("CREATED")
                .build();

        standardScenarioService.sendSync(order, "idempotent");
        awaitUntil(() -> idempotentConsumer.getSuccessProcessCount().get() >= 1);
        assertThat(idempotentConsumer.getSuccessProcessCount().get()).isEqualTo(1);
        assertThat(idempotentConsumer.getDuplicateInterceptCount().get()).isEqualTo(0);

        // 重复发送同一订单 -> 模拟网络重发
        standardScenarioService.sendSync(order, "idempotent");
        awaitUntil(() -> idempotentConsumer.getDuplicateInterceptCount().get() >= 1);
        assertThat(idempotentConsumer.getSuccessProcessCount().get()).isEqualTo(1);
        assertThat(idempotentConsumer.getDuplicateInterceptCount().get()).isEqualTo(1);
    }

    // =========================================================================
    // 场景 19：SimpleConsumer 流式拉取、租约续期与显式 ACK
    // =========================================================================

    @Test
    @DisplayName("场景 19：SimpleConsumer 微批流式拉取、动态租约续期、显式 ACK 与函数式闭包")
    void testSimpleConsumerStreamingAndLeaseRenewal() {
        String topic = TradeRocketmqProducer.TOPIC_ORDER;
        String group = "sample_simple_group";
        String tag = "simple_pull";

        OrderMessage order1 = OrderMessage.builder().orderId("ORD_S1").customerName("拉取买家1").amount(10.0).status("CREATED").build();
        rocketmqTemplate.send(topic, tag, "ORD_S1", order1);

        // 1. 流式拉取
        List<MessageView> pulled = simpleConsumerPullScenarioService.pullMessages(topic, group, tag, 10, Duration.ofSeconds(30));
        assertThat(pulled).isNotEmpty();
        MessageView msg = pulled.get(0);
        assertThat(msg.getMessageId()).isNotNull();

        // 2. 动态租约续期
        simpleConsumerPullScenarioService.renewLease(group, msg, Duration.ofSeconds(60));
        if (testBroker != null) {
            assertThat(testBroker.getRenewedMessageIds()).contains(msg.getMessageId().toString());
        }

        // 3. 显式确认
        simpleConsumerPullScenarioService.acknowledge(group, msg);
        if (testBroker != null) {
            assertThat(testBroker.getAckedMessageIds()).contains(msg.getMessageId().toString());
        }

        // 4. 函数式消费闭包
        OrderMessage order2 = OrderMessage.builder().orderId("ORD_S2").customerName("拉取买家2").amount(20.0).status("CREATED").build();
        rocketmqTemplate.send(topic, tag, "ORD_S2", order2);

        int executed = simpleConsumerPullScenarioService.pullAndExecute(topic, group, tag, OrderMessage.class, order -> {
            assertThat(order.getOrderId()).isNotNull();
        });
        assertThat(executed).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("场景 19b：SimpleConsumer 租约自动续期看门狗、单条异常隔离（含 IdempotentException 静默 ACK）与动态过滤刷新")
    void testSimpleConsumerWatchdogFaultIsolationAndDynamicFilterRefresh() {
        simpleConsumerPullScenarioService.clearCache();
        String topic = TradeRocketmqProducer.TOPIC_ORDER;

        // 1. 动态过滤表达式订阅刷新验证
        String dynamicGroup = "sample_dynamic_filter_group";
        OrderMessage alpha = OrderMessage.builder().orderId("ORD_DYN_ALPHA").customerName("A").amount(10.0).status("CREATED").build();
        OrderMessage beta = OrderMessage.builder().orderId("ORD_DYN_BETA").customerName("B").amount(20.0).status("CREATED").build();
        rocketmqTemplate.send(topic, "dyn_alpha", "ORD_DYN_ALPHA", alpha);
        rocketmqTemplate.send(topic, "dyn_beta", "ORD_DYN_BETA", beta);

        List<MessageView> firstBatch = simpleConsumerPullScenarioService.pullMessages(topic, dynamicGroup, "dyn_alpha", 10, Duration.ofSeconds(30));
        assertThat(firstBatch).hasSize(1);
        assertThat(firstBatch.get(0).getTag()).contains("dyn_alpha");
        simpleConsumerPullScenarioService.acknowledge(dynamicGroup, firstBatch.get(0));

        // 同消费组同 Topic 切换为 dyn_beta，应主动刷新底层 SimpleConsumer.subscribe 并拉取到 dyn_beta
        List<MessageView> secondBatch = simpleConsumerPullScenarioService.pullMessages(topic, dynamicGroup, "dyn_beta", 10, Duration.ofSeconds(30));
        assertThat(secondBatch).hasSize(1);
        assertThat(secondBatch.get(0).getTag()).contains("dyn_beta");
        simpleConsumerPullScenarioService.acknowledge(dynamicGroup, secondBatch.get(0));
        if (testBroker != null) {
            assertThat(testBroker.getSimpleSubscribeRecords())
                    .contains(dynamicGroup + "#" + topic + "#TAG:dyn_beta");
        }

        // 2. 单条故障隔离与 IdempotentException 静默 ACK 验证
        String isoGroup = "sample_resilient_group";
        OrderMessage ok1 = OrderMessage.builder().orderId("ORD_RES_OK_1").customerName("N1").amount(11.0).status("CREATED").build();
        OrderMessage idem = OrderMessage.builder().orderId("ORD_RES_IDEM").customerName("ID").amount(22.0).status("IDEMPOTENT").build();
        OrderMessage fail = OrderMessage.builder().orderId("ORD_RES_FAIL").customerName("FL").amount(33.0).status("FAIL").build();
        OrderMessage ok2 = OrderMessage.builder().orderId("ORD_RES_OK_2").customerName("N2").amount(44.0).status("CREATED").build();
        rocketmqTemplate.send(topic, "resilient_tag", "ORD_RES_OK_1", ok1);
        rocketmqTemplate.send(topic, "resilient_tag", "ORD_RES_IDEM", idem);
        rocketmqTemplate.send(topic, "resilient_tag", "ORD_RES_FAIL", fail);
        rocketmqTemplate.send(topic, "resilient_tag", "ORD_RES_OK_2", ok2);

        SimpleConsumeReportVO isoReport = simpleConsumerPullScenarioService.pullAndExecuteResilient(
                topic, isoGroup, "resilient_tag", 10, Duration.ofSeconds(30), Duration.ZERO);

        assertThat(isoReport.getSettledCount()).isEqualTo(3);
        assertThat(isoReport.getConsumedCount()).isEqualTo(2);
        assertThat(isoReport.getIdempotentCount()).isEqualTo(1);
        assertThat(isoReport.getFailedCount()).isEqualTo(1);
        assertThat(isoReport.getConsumedOrderIds()).containsExactly("ORD_RES_OK_1", "ORD_RES_OK_2");
        assertThat(isoReport.getFailedOrderIds()).containsExactly("ORD_RES_FAIL");

        // 3. 长任务期间受控租约自动续期看门狗验证 (invisibleDuration=2s -> 周期 1s)
        String renewGroup = "sample_watchdog_group";
        OrderMessage slowOrder = OrderMessage.builder().orderId("ORD_WATCHDOG_1").customerName("SLOW").amount(55.0).status("CREATED").build();
        rocketmqTemplate.send(topic, "watchdog_tag", "ORD_WATCHDOG_1", slowOrder);

        SimpleConsumeReportVO renewReport = simpleConsumerPullScenarioService.pullAndExecuteResilient(
                topic, renewGroup, "watchdog_tag", 5, Duration.ofSeconds(2), Duration.ofMillis(1400));
        assertThat(renewReport.getSettledCount()).isEqualTo(1);
        assertThat(renewReport.getConsumedOrderIds()).containsExactly("ORD_WATCHDOG_1");
        if (testBroker != null) {
            assertThat(testBroker.getRenewedMessageIds()).isNotEmpty();
        }
    }

    // =========================================================================
    // 场景 20：运行期容器运维管控与死信队列治理
    // =========================================================================

    @Test
    @DisplayName("场景 20：运行期消费容器运维 (pause/resume) 与死信队列受控重放治理及防二次雪崩")
    void testContainerOpsAndDeadLetterGovernance() {
        // 1. 运行期容器运维管控（不可变快照观测 + 短时削峰 pause/resume + 长时维护 stop/start）
        String endpointId = "sample_order_group#sample_order_topic";
        assertThat(containerOpsScenarioService.getEndpointIds()).contains(endpointId, "sample-sql-order-listener");
        assertThat(containerOpsScenarioService.getContainerStatuses())
                .extracting(io.github.atengk.boot.rocketmq.model.RocketmqContainerStatus::endpointId)
                .contains(endpointId, "sample-sql-order-listener");

        var sqlStatus = containerOpsScenarioService.getContainerStatus("sample-sql-order-listener");
        assertThat(sqlStatus).isNotNull();
        assertThat(sqlStatus.filterType()).isEqualTo("SQL92");
        assertThat(sqlStatus.filterExpression()).isEqualTo("amount > 100");
        assertThat(sqlStatus.listenerMode()).isEqualTo("PUSH_CONSUMER");
        assertThat(sqlStatus.autoStartup()).isTrue();
        assertThat(sqlStatus.running()).isTrue();
        assertThat(sqlStatus.paused()).isFalse();

        // 短时限流削峰：pause/resume 保持底层运行连接（running=true, paused=true）
        boolean paused = containerOpsScenarioService.pauseEndpoint(endpointId);
        assertThat(paused).isTrue();
        assertThat(containerOpsScenarioService.isPaused(endpointId)).isTrue();
        var pausedSnapshot = containerOpsScenarioService.getContainerStatus(endpointId);
        assertThat(pausedSnapshot).isNotNull();
        assertThat(pausedSnapshot.running()).isTrue();
        assertThat(pausedSnapshot.paused()).isTrue();

        boolean resumed = containerOpsScenarioService.resumeEndpoint(endpointId);
        assertThat(resumed).isTrue();
        assertThat(containerOpsScenarioService.isPaused(endpointId)).isFalse();

        // 长时维护窗口停机：stop/start 物理断连（running=false -> running=true）
        assertThat(containerOpsScenarioService.stopEndpoint(endpointId)).isTrue();
        var stoppedSnapshot = containerOpsScenarioService.getContainerStatus(endpointId);
        assertThat(stoppedSnapshot).isNotNull();
        assertThat(stoppedSnapshot.running()).isFalse();
        assertThat(containerOpsScenarioService.startEndpoint(endpointId)).isTrue();
        assertThat(containerOpsScenarioService.getContainerStatus(endpointId).running()).isTrue();

        // 2. 死信队列治理与受控重放
        String dlqGroup = "sample_order_group";
        String dlqTopic = "%DLQ%" + dlqGroup;

        // 2.1 只读探查 (零 ACK)
        Map<String, String> peekProps = Map.of(
                "X-Original-Topic", TradeRocketmqProducer.TOPIC_ORDER,
                "X-Biz-Key", "ORD_DLQ_PEEK"
        );
        if (testBroker != null) {
            MessageView peekMsg = testBroker.createMockMessageView(
                    dlqTopic, "DLQ_PEEK_001", "{\"orderId\":\"ORD_DLQ_PEEK\",\"customerName\":\"死信探查买家\"}".getBytes(StandardCharsets.UTF_8),
                    peekProps, "create", null
            );
            testBroker.enqueueDlqMessage(dlqGroup, peekMsg);
        } else {
            rocketmqTemplate.send(dlqTopic, "create", "ORD_DLQ_PEEK", "{\"orderId\":\"ORD_DLQ_PEEK\",\"customerName\":\"死信探查买家\"}", peekProps);
        }
        List<MessageView> peeked = deadLetterReplayScenarioService.peekDeadLetters(dlqGroup, 10);
        assertThat(peeked).isNotEmpty();
        if (testBroker != null) {
            assertThat(testBroker.getAckedMessageIds()).doesNotContain("DLQ_PEEK_001");
        }

        // 2.2 受控重放 -> 投递至原业务主题并确认原死信
        Map<String, String> dlqProps = Map.of(
                "X-Original-Topic", TradeRocketmqProducer.TOPIC_ORDER,
                "X-Biz-Key", "ORD_DLQ_001"
        );
        if (testBroker != null) {
            MessageView dlqMsg = testBroker.createMockMessageView(
                    dlqTopic, "DLQ_001", "{\"orderId\":\"ORD_DLQ_001\",\"customerName\":\"死信重放买家\"}".getBytes(StandardCharsets.UTF_8),
                    dlqProps, "create", null
            );
            testBroker.enqueueDlqMessage(dlqGroup, dlqMsg);
        } else {
            rocketmqTemplate.send(dlqTopic, "create", "ORD_DLQ_001", "{\"orderId\":\"ORD_DLQ_001\",\"customerName\":\"死信重放买家\"}", dlqProps);
        }
        DeadLetterReplayResult replayResult = deadLetterReplayScenarioService.replayDeadLetters(dlqGroup, 10);
        assertThat(replayResult.getSuccessCount()).isGreaterThanOrEqualTo(1);
        if (testBroker != null) {
            assertThat(testBroker.getAckedMessageIds()).contains("DLQ_001");
        }

        // 2.3 测试重放至指定分流主题
        Map<String, String> topicDlqProps = Map.of("X-Original-Topic", TradeRocketmqProducer.TOPIC_ORDER, "X-Biz-Key", "ORD_DLQ_TOPIC");
        if (testBroker != null) {
            MessageView dlqMsgTopic = testBroker.createMockMessageView(
                    dlqTopic, "DLQ_TOPIC_001", "{\"orderId\":\"ORD_DLQ_TOPIC\"}".getBytes(StandardCharsets.UTF_8),
                    topicDlqProps, "create", null
            );
            testBroker.enqueueDlqMessage(dlqGroup, dlqMsgTopic);
        } else {
            rocketmqTemplate.send(dlqTopic, "create", "ORD_DLQ_TOPIC", "{\"orderId\":\"ORD_DLQ_TOPIC\"}", topicDlqProps);
        }
        DeadLetterReplayResult topicReplayResult = deadLetterReplayScenarioService.replayDeadLettersToTopic(dlqGroup, 10, TradeRocketmqProducer.TOPIC_ORDER);
        assertThat(topicReplayResult.getSuccessCount()).isGreaterThanOrEqualTo(1);

        // 2.4 测试死信批量丢弃清理
        Map<String, String> discardDlqProps = Map.of("X-Original-Topic", TradeRocketmqProducer.TOPIC_ORDER, "X-Biz-Key", "ORD_DISCARD");
        if (testBroker != null) {
            MessageView discardMsg = testBroker.createMockMessageView(
                    dlqTopic, "DLQ_DISCARD_001", "{\"orderId\":\"ORD_DISCARD\"}".getBytes(StandardCharsets.UTF_8),
                    discardDlqProps, "create", null
            );
            testBroker.enqueueDlqMessage(dlqGroup, discardMsg);
        } else {
            rocketmqTemplate.send(dlqTopic, "create", "ORD_DISCARD", "{\"orderId\":\"ORD_DISCARD\"}", discardDlqProps);
        }
        int discarded = deadLetterReplayScenarioService.discardDeadLetters(dlqGroup, 10);
        assertThat(discarded).isGreaterThanOrEqualTo(1);

        // 3. 防二次雪崩熔断防线测试
        Map<String, String> avalancheProps = Map.of(
                "X-Original-Topic", TradeRocketmqProducer.TOPIC_ORDER,
                "X-Biz-Key", "ORD_AVALANCHE",
                RocketmqConstants.HEADER_DEAD_LETTER_REPLAY_COUNT, "3" // 已达最大上限
        );
        if (testBroker != null) {
            MessageView avalancheMsg = testBroker.createMockMessageView(
                    dlqTopic, "DLQ_AVALANCHE_001", "{\"orderId\":\"ORD_AVALANCHE\"}".getBytes(StandardCharsets.UTF_8),
                    avalancheProps, "create", null
            );
            testBroker.enqueueDlqMessage(dlqGroup, avalancheMsg);
        } else {
            rocketmqTemplate.send(dlqTopic, "create", "ORD_AVALANCHE", "{\"orderId\":\"ORD_AVALANCHE\"}", avalancheProps);
        }

        DeadLetterReplayResult avalancheResult = deadLetterReplayScenarioService.replayDeadLetters(dlqGroup, 10);
        assertThat(avalancheResult.getBlockedCount()).isGreaterThanOrEqualTo(1);
    }

    // =========================================================================
    // 场景 24：毒丸转储 %DLQ% 回调、结构化诊断窥视、损坏载荷对抗与开关关闭对比
    // =========================================================================

    @Test
    @DisplayName("场景 24：毒丸自动转储 %DLQ% + 回调审计 + 结构化诊断窥视 + 重放头清洗 + 开关关闭对比")
    void testPoisonPillDlqForwardingDiagnosedPeekAndSwitchOffComparison() throws Exception {
        String poisonGroup = "sample_poison_group";
        String auditReplayTopic = "sample_dlq_replay_audit_topic";

        // 1. 默认开启 forward-poison-pill-to-dlq=true：投递 FATAL / MALFORMED_JSON / CORRUPTED_GZIP 毒丸
        DeadLetterPoisonPillRequest fatalReq = new DeadLetterPoisonPillRequest();
        fatalReq.setMode("FATAL");
        fatalReq.setOrderId("ORD-E2E-POISON-FATAL");
        OrderProduceResponse fatalResp = deadLetterReplayScenarioService.triggerPoisonPill(fatalReq);
        assertThat(fatalResp.getStatus()).isEqualTo("POISON_PILL_DISPATCHED:FATAL");

        DeadLetterPoisonPillRequest jsonReq = new DeadLetterPoisonPillRequest();
        jsonReq.setMode("MALFORMED_JSON");
        jsonReq.setOrderId("ORD-E2E-POISON-JSON");
        jsonReq.setRawPayload("{e2e-corrupted-json");
        deadLetterReplayScenarioService.triggerPoisonPill(jsonReq);

        DeadLetterPoisonPillRequest gzipReq = new DeadLetterPoisonPillRequest();
        gzipReq.setMode("CORRUPTED_GZIP");
        gzipReq.setOrderId("ORD-E2E-POISON-GZIP");
        deadLetterReplayScenarioService.triggerPoisonPill(gzipReq);

        // 消费方法未正常计数，全部由 invoker 拦截转储
        assertThat(poisonConsumer.getPoisonProcessCount().get()).isZero();

        // 2. 验证 SPI 回调捕获了 3 条转储事件
        assertThat(poisonPillCallbackRecorder).isNotNull();
        assertThat(poisonPillCallbackRecorder.getEvents()).hasSize(3);
        assertThat(poisonPillCallbackRecorder.getEvents())
                .allSatisfy(event -> {
                    assertThat(event.forwardedToDlq()).isTrue();
                    assertThat(event.dlqTopic()).isEqualTo(RocketmqConstants.DLQ_TOPIC_PREFIX + poisonGroup);
                    assertThat(event.topic()).isEqualTo(TradeRocketmqProducer.TOPIC_ORDER);
                });

        // 3. 结构化诊断窥视：验证 FATAL_EXCEPTION / DESERIALIZATION_FAILED / GZIP_DECOMPRESS_FAILED 与 HEX 预览
        List<RocketmqDeadLetterRecord> diagnosed = deadLetterReplayScenarioService.peekDiagnosedDeadLetters(poisonGroup, 20);
        assertThat(diagnosed).hasSize(3);

        RocketmqDeadLetterRecord fatalRecord = diagnosed.stream()
                .filter(r -> "FATAL_EXCEPTION".equals(r.reason()))
                .findFirst()
                .orElseThrow();
        assertThat(fatalRecord.exceptionClassName()).isEqualTo(IllegalArgumentException.class.getName());
        assertThat(fatalRecord.exceptionMessage()).contains("致命数据格式错误");
        assertThat(fatalRecord.hexPreview()).isFalse();
        assertThat(fatalRecord.originalTopic()).isEqualTo(TradeRocketmqProducer.TOPIC_ORDER);
        assertThat(fatalRecord.consumerGroup()).isEqualTo(poisonGroup);

        RocketmqDeadLetterRecord gzipRecord = diagnosed.stream()
                .filter(r -> "GZIP_DECOMPRESS_FAILED".equals(r.reason()))
                .findFirst()
                .orElseThrow();
        assertThat(gzipRecord.hexPreview()).isTrue();
        assertThat(gzipRecord.payloadSizeBytes()).isEqualTo(8);
        assertThat(gzipRecord.payloadAsString()).startsWith("[HEX-PREVIEW size=8B]");

        RocketmqDeadLetterRecord jsonRecord = diagnosed.stream()
                .filter(r -> "DESERIALIZATION_FAILED".equals(r.reason()))
                .findFirst()
                .orElseThrow();
        assertThat(jsonRecord.hexPreview()).isFalse();
        assertThat(jsonRecord.payloadAsString()).isEqualTo("{e2e-corrupted-json");

        // 4. 受控重放至安全审计主题：非损坏载荷成功重放 (2条) 且历史故障诊断头已彻底剥离，损坏 GZIP 被防御拦截 (1条)
        DeadLetterReplayResult replayResult =
                deadLetterReplayScenarioService.replayDeadLettersToTopic(poisonGroup, 20, auditReplayTopic);
        assertThat(replayResult.getSuccessCount()).isEqualTo(2);
        assertThat(replayResult.getFailedCount()).isEqualTo(1);

        if (testBroker != null) {
            List<MessageView> replayedMessages = testBroker.getSentMessages(auditReplayTopic);
            assertThat(replayedMessages).hasSize(2);
            assertThat(replayedMessages).allSatisfy(msg -> {
                Map<String, String> props = msg.getProperties();
                assertThat(props).doesNotContainKeys(
                        RocketmqConstants.HEADER_DEAD_LETTER_REASON,
                        RocketmqConstants.HEADER_DEAD_LETTER_EXCEPTION_CLASS,
                        RocketmqConstants.HEADER_DEAD_LETTER_EXCEPTION_MESSAGE,
                        RocketmqConstants.HEADER_DEAD_LETTER_TIMESTAMP,
                        RocketmqConstants.HEADER_DEAD_LETTER_DELIVERY_ATTEMPT
                );
                assertThat(props).containsEntry(RocketmqConstants.HEADER_DEAD_LETTER_REPLAYED, "true");
                assertThat(props).containsEntry(RocketmqConstants.HEADER_DEAD_LETTER_REPLAY_COUNT, "1");
            });

            // 5. 开关关闭对比 (forward-poison-pill-to-dlq=false)：仍返回 SUCCESS 静默确认并回调通知，但不转储至 %DLQ%
            String switchOffGroup = "sample_poison_switchoff_group";
            SamplePoisonPillCallbackRecorder switchOffRecorder = new SamplePoisonPillCallbackRecorder();
            java.lang.reflect.Method consumeMethod = PoisonPillRocketmqConsumer.class.getMethod("onPoisonMessage", OrderMessage.class);
            RocketmqListenerInvoker switchOffInvoker = new RocketmqListenerInvoker(
                    poisonConsumer,
                    consumeMethod,
                    TradeRocketmqProducer.TOPIC_ORDER,
                    switchOffGroup,
                    null,
                    false,
                    rocketmqTemplate.getProducer(),
                    switchOffRecorder
            );

            MessageView poisonView = InMemoryRocketmqBroker.createMockMessageView(
                    TradeRocketmqProducer.TOPIC_ORDER,
                    "MSG-SWITCHOFF-POISON-001",
                    "{not-valid-json".getBytes(StandardCharsets.UTF_8),
                    Map.of("X-Biz-Key", "ORD-SWITCHOFF-001"),
                    "poison",
                    null
            );

            ConsumeResult result = switchOffInvoker.consume(poisonView);
            assertThat(result).isEqualTo(ConsumeResult.SUCCESS);
            assertThat(testBroker.getSentMessages(RocketmqConstants.DLQ_TOPIC_PREFIX + switchOffGroup)).isEmpty();
            assertThat(switchOffRecorder.getEvents()).hasSize(1);
            assertThat(switchOffRecorder.getEvents().get(0).forwardedToDlq()).isFalse();
            assertThat(switchOffRecorder.getEvents().get(0).dlqTopic())
                    .isEqualTo(RocketmqConstants.DLQ_TOPIC_PREFIX + switchOffGroup);
        }
    }

    // =========================================================================
    // 测试环境配置与 TestDouble 注册
    // =========================================================================

    @TestConfiguration(proxyBeanMethods = false)
    public static class TestRocketmqConfig {

        @Bean
        @Primary
        @Profile("!local")
        public InMemoryRocketmqBroker inMemoryRocketmqBroker() {
            return new InMemoryRocketmqBroker();
        }

        @Bean
        @Primary
        @Profile("!local")
        public Producer testProducer(ObjectProvider<InMemoryRocketmqBroker> brokerProvider) {
            InMemoryRocketmqBroker broker = brokerProvider.getIfAvailable();
            return broker != null ? broker.createProducer() : null;
        }

        @Bean
        @Primary
        public DataSource testDataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("sample_rocketmq_test_db")
                    .build();
        }

        @Bean
        @Primary
        public PlatformTransactionManager testTransactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        public RecordingTransactionFailureCallback recordingTransactionFailureCallback() {
            return new RecordingTransactionFailureCallback();
        }
    }

    /**
     * 事务后置发信失败记录（用于验证 6 参 SPI 回调全上下文参数）
     */
    public record FailureRecord(
            String topic,
            String tag,
            String bizKey,
            Object payload,
            String traceId,
            Throwable cause
    ) {}

    /**
     * 测试用 6 参事务补偿回调记录器
     */
    public static class RecordingTransactionFailureCallback implements RocketmqTransactionFailureCallback {

        private final List<FailureRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void onFailure(String topic, String bizKey, Object payload, Throwable cause) {
            records.add(new FailureRecord(topic, null, bizKey, payload, null, cause));
        }

        @Override
        public void onFailure(String topic, String tag, String bizKey, Object payload, String traceId, Throwable cause) {
            records.add(new FailureRecord(topic, tag, bizKey, payload, traceId, cause));
        }

        public List<FailureRecord> getRecords() {
            return records;
        }

        public void clear() {
            records.clear();
        }
    }
}
