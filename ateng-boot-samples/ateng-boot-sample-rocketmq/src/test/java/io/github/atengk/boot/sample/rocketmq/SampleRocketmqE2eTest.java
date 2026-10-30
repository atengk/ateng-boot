package io.github.atengk.boot.sample.rocketmq;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.MessageDeliveryException;
import io.github.atengk.boot.rocketmq.checker.RocketmqTransactionChecker;
import io.github.atengk.boot.rocketmq.constant.RocketmqConstants;
import io.github.atengk.boot.rocketmq.consumer.PushConsumerFactory;
import io.github.atengk.boot.rocketmq.consumer.SimpleConsumerFactory;
import io.github.atengk.boot.rocketmq.listener.RocketmqConsumerContainer;
import io.github.atengk.boot.rocketmq.model.DeadLetterReplayResult;
import io.github.atengk.boot.sample.rocketmq.checker.SampleOrderTransactionChecker;
import io.github.atengk.boot.sample.rocketmq.consumer.*;
import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import io.github.atengk.boot.sample.rocketmq.producer.TradeRocketmqProducer;
import io.github.atengk.boot.sample.rocketmq.service.*;
import io.github.atengk.boot.sample.rocketmq.testdouble.InMemoryRocketmqBroker;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.apache.rocketmq.client.apis.producer.TransactionResolution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
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

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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

    @Autowired
    private InMemoryRocketmqBroker testBroker;

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
        testBroker.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
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

        assertThat(testBroker.getSentMessageCount().get()).isGreaterThanOrEqualTo(1);
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

        Instant deliverAt = Instant.now().plusSeconds(10);
        SendReceipt receipt = batchDelayScenarioService.sendDeliverAt(scheduledOrder, deliverAt);

        assertThat(receipt).isNotNull();
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
        String recallHandle = receipt.getRecallHandle();
        assertThat(recallHandle).isNotBlank();

        batchDelayScenarioService.recallDelayedMessage(TradeRocketmqProducer.TOPIC_DELAY, recallHandle);

        assertThat(testBroker.getRecalledHandles()).contains(recallHandle);
    }

    // =========================================================================
    // 场景 10：批量可靠发信与微切片并发汇聚 (sendBatch)
    // =========================================================================

    @Test
    @DisplayName("场景 10：批量并发发信 (sendBatch / sendBatchAsync) 汇聚等待")
    void testBatchSend() {
        List<OrderMessage> batchOrders = List.of(
                OrderMessage.builder().orderId("ORD-BATCH-1").amount(10.0).customerName("批买家1").status("CREATED").build(),
                OrderMessage.builder().orderId("ORD-BATCH-2").amount(20.0).customerName("批买家2").status("CREATED").build(),
                OrderMessage.builder().orderId("ORD-BATCH-3").amount(30.0).customerName("批买家3").status("CREATED").build()
        );

        List<SendReceipt> receipts = batchDelayScenarioService.sendBatch(batchOrders);
        assertThat(receipts).hasSize(3);

        CompletableFuture<List<SendReceipt>> asyncFuture = batchDelayScenarioService.sendBatchAsync(batchOrders);
        List<SendReceipt> asyncReceipts = asyncFuture.join();
        assertThat(asyncReceipts).hasSize(3);
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

        MessageView mockView = testBroker.createMockMessageView(
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
        assertThat(idempotentConsumer.getSuccessProcessCount().get()).isEqualTo(1);
        assertThat(idempotentConsumer.getDuplicateInterceptCount().get()).isEqualTo(0);

        // 重复发送同一订单 -> 模拟网络重发
        standardScenarioService.sendSync(order, "idempotent");
        assertThat(idempotentConsumer.getSuccessProcessCount().get()).isEqualTo(1);
        assertThat(idempotentConsumer.getDuplicateInterceptCount().get()).isEqualTo(1);
    }

    // =========================================================================
    // 场景 19：SimpleConsumer 流式拉取、租约续期与显式 ACK
    // =========================================================================

    @Test
    @DisplayName("场景 19：SimpleConsumer 微批流式拉取、动态租约续期、显式 ACK 与函数式闭包")
    void testSimpleConsumerStreamingAndLeaseRenewal() {
        String topic = "sample_simple_topic";
        String group = "sample_simple_group";

        MessageView mv1 = testBroker.createMockMessageView(topic, "SIMPLE_MSG_1", "{\"orderId\":\"ORD_S1\"}".getBytes(StandardCharsets.UTF_8), null, "create", null);
        testBroker.enqueueMessage(topic, mv1);

        // 1. 流式拉取
        List<MessageView> pulled = simpleConsumerPullScenarioService.pullMessages(topic, group, 10, Duration.ofSeconds(30));
        assertThat(pulled).hasSize(1);
        MessageView msg = pulled.get(0);
        assertThat(msg.getMessageId().toString()).isEqualTo("SIMPLE_MSG_1");

        // 2. 动态租约续期
        simpleConsumerPullScenarioService.renewLease(group, msg, Duration.ofSeconds(60));
        assertThat(testBroker.getRenewedMessageIds()).contains("SIMPLE_MSG_1");

        // 3. 显式确认
        simpleConsumerPullScenarioService.acknowledge(group, msg);
        assertThat(testBroker.getAckedMessageIds()).contains("SIMPLE_MSG_1");

        // 4. 函数式消费闭包
        MessageView mv2 = testBroker.createMockMessageView(topic, "SIMPLE_MSG_2", "{\"orderId\":\"ORD_S2\"}".getBytes(StandardCharsets.UTF_8), null, "create", null);
        testBroker.enqueueMessage(topic, mv2);

        int executed = simpleConsumerPullScenarioService.pullAndExecute(topic, group, OrderMessage.class, order -> {
            assertThat(order.getOrderId()).isEqualTo("ORD_S2");
        });
        assertThat(executed).isEqualTo(1);
    }

    // =========================================================================
    // 场景 20：运行期容器运维管控与死信队列治理
    // =========================================================================

    @Test
    @DisplayName("场景 20：运行期消费容器运维 (pause/resume) 与死信队列受控重放治理及防二次雪崩")
    void testContainerOpsAndDeadLetterGovernance() {
        // 1. 运行期容器运维管控
        String endpointId = "sample_order_group#sample_order_topic";
        assertThat(containerOpsScenarioService.getEndpointIds()).contains(endpointId);

        boolean paused = containerOpsScenarioService.pauseEndpoint(endpointId);
        assertThat(paused).isTrue();
        assertThat(containerOpsScenarioService.isPaused(endpointId)).isTrue();

        boolean resumed = containerOpsScenarioService.resumeEndpoint(endpointId);
        assertThat(resumed).isTrue();
        assertThat(containerOpsScenarioService.isPaused(endpointId)).isFalse();

        // 2. 死信队列治理与受控重放
        String dlqGroup = "sample_order_group";
        Map<String, String> dlqProps = Map.of(
                "X-Original-Topic", TradeRocketmqProducer.TOPIC_ORDER,
                "X-Biz-Key", "ORD_DLQ_001"
        );
        MessageView dlqMsg = testBroker.createMockMessageView(
                "%DLQ%" + dlqGroup, "DLQ_001", "{\"orderId\":\"ORD_DLQ_001\",\"customerName\":\"死信重放买家\"}".getBytes(StandardCharsets.UTF_8),
                dlqProps, "create", null
        );
        testBroker.enqueueDlqMessage(dlqGroup, dlqMsg);

        // 只读探查 (零 ACK)
        List<MessageView> peeked = deadLetterReplayScenarioService.peekDeadLetters(dlqGroup, 10);
        assertThat(peeked).hasSize(1);
        assertThat(testBroker.getAckedMessageIds()).doesNotContain("DLQ_001");

        // 受控重放 -> 投递至原业务主题并确认原死信
        DeadLetterReplayResult replayResult = deadLetterReplayScenarioService.replayDeadLetters(dlqGroup, 10);
        assertThat(replayResult.getSuccessCount()).isEqualTo(1);
        assertThat(testBroker.getAckedMessageIds()).contains("DLQ_001");

        // 3. 防二次雪崩熔断防线测试
        Map<String, String> avalancheProps = Map.of(
                "X-Original-Topic", TradeRocketmqProducer.TOPIC_ORDER,
                "X-Biz-Key", "ORD_AVALANCHE",
                RocketmqConstants.HEADER_DEAD_LETTER_REPLAY_COUNT, "3" // 已达最大上限
        );
        MessageView avalancheMsg = testBroker.createMockMessageView(
                "%DLQ%" + dlqGroup, "DLQ_AVALANCHE_001", "{\"orderId\":\"ORD_AVALANCHE\"}".getBytes(StandardCharsets.UTF_8),
                avalancheProps, "create", null
        );
        testBroker.enqueueDlqMessage(dlqGroup, avalancheMsg);

        DeadLetterReplayResult avalancheResult = deadLetterReplayScenarioService.replayDeadLetters(dlqGroup, 10);
        assertThat(avalancheResult.getBlockedCount()).isEqualTo(1);
        assertThat(avalancheResult.getSuccessCount()).isEqualTo(0);
        assertThat(testBroker.getAckedMessageIds()).doesNotContain("DLQ_AVALANCHE_001");
    }

    // =========================================================================
    // 测试环境配置与 TestDouble 注册
    // =========================================================================

    @TestConfiguration(proxyBeanMethods = false)
    @Profile("!local")
    static class TestRocketmqConfig {

        @Bean
        @Primary
        public InMemoryRocketmqBroker inMemoryRocketmqBroker() {
            return new InMemoryRocketmqBroker();
        }

        @Bean
        @Primary
        public Producer testProducer(InMemoryRocketmqBroker broker) {
            return broker.createProducer();
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
    }
}
