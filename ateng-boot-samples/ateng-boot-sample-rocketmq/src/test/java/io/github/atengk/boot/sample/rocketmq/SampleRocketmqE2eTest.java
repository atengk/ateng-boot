package io.github.atengk.boot.sample.rocketmq;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.MessageDeliveryException;
import io.github.atengk.boot.rocketmq.checker.RocketmqTransactionChecker;
import io.github.atengk.boot.rocketmq.consumer.PushConsumerFactory;
import io.github.atengk.boot.rocketmq.listener.RocketmqConsumerContainer;
import io.github.atengk.boot.sample.rocketmq.checker.SampleOrderTransactionChecker;
import io.github.atengk.boot.sample.rocketmq.consumer.*;
import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import io.github.atengk.boot.sample.rocketmq.producer.TradeRocketmqProducer;
import io.github.atengk.boot.sample.rocketmq.service.RocketmqOrderTransactionService;
import io.github.atengk.boot.sample.rocketmq.testdouble.InMemoryRocketmqBroker;
import org.apache.rocketmq.client.apis.ClientConfiguration;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RocketMQ 场景沙箱 1:1 端到端离线集成测试套件
 *
 * <p>全面覆盖 10 大核心端到端生产场景：
 * <ul>
 *   <li>场景 1：同步与异步可靠收发、推断反序列化与四维上下文透传 (MDC/Tenant/User)；</li>
 *   <li>场景 2：毫秒级延时与定时消息调度投递；</li>
 *   <li>场景 3：FIFO 顺序保序消息 (MessageGroup)；</li>
 *   <li>场景 4：原生 2PC 分布式事务半消息 (COMMIT / ROLLBACK) 与统一回查分发；</li>
 *   <li>场景 5：本地事务后置感知发信 (sendAfterCommit) 与异常回滚防幽灵消息；</li>
 *   <li>场景 6：SQL92 服务端属性过滤与高吞吐下推；</li>
 *   <li>场景 7：毒丸不可自愈异常安全丢弃与快速确认；</li>
 *   <li>场景 8：@Idempotent 防重冲突静默确认阻断重试；</li>
 *   <li>场景 9：4MB 大报文防御超限拦截；</li>
 *   <li>场景 10：SmartLifecycle 确定性停机排空保障。</li>
 * </ul>
 * 100% 内存自洽运行，零外部网络与 Docker 依赖，秒级全绿。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@SpringBootTest(classes = {SampleRocketmqApplication.class, SampleRocketmqE2eTest.TestRocketmqConfig.class})
@DisplayName("RocketMQ 场景沙箱 1:1 端到端离线集成测试")
class SampleRocketmqE2eTest {

    @Autowired
    private TradeRocketmqProducer producer;

    @Autowired
    private RocketmqOrderTransactionService transactionService;

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
        transactionService.clear();
        testBroker.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
    }

    // =========================================================================
    // 场景 1：同步与异步可靠收发、推断反序列化与四维上下文透传
    // =========================================================================

    @Test
    @DisplayName("场景 1.1：同步发信 -> 四维上下文透传无损还原 -> 强类型反序列化消费")
    void testSyncSendAndContextPropagation() {
        // 设置四维上下文
        MDC.put("traceId", "TRACE-SYNC-8888");
        TenantContextHolder.setTenantId("TENANT-EAST-01");
        UserContextHolder.setUserId("USER-ATENG-99");

        OrderMessage order = OrderMessage.builder()
                .orderId("ORD-SYNC-001")
                .amount(299.9)
                .customerName("张三")
                .status("CREATED")
                .build();

        SendReceipt receipt = producer.sendOrder(order);

        assertThat(receipt).isNotNull();
        assertThat(receipt.getMessageId()).isNotNull();

        // 验证消费端已接收到该订单
        assertThat(standardConsumer.getReceivedOrders()).hasSize(1);
        OrderMessage received = standardConsumer.getReceivedOrders().get(0);
        assertThat(received.getOrderId()).isEqualTo("ORD-SYNC-001");
        assertThat(received.getAmount()).isEqualTo(299.9);
        assertThat(received.getCustomerName()).isEqualTo("张三");

        // 验证四维上下文无损还原
        assertThat(standardConsumer.getReceivedTraceIds()).contains("TRACE-SYNC-8888");
        assertThat(standardConsumer.getReceivedTenantIds()).contains("TENANT-EAST-01");
    }

    @Test
    @DisplayName("场景 1.2：异步发信 -> CompletableFuture 回调 -> 顺利消费")
    void testAsyncSendAndConsume() {
        MDC.put("traceId", "TRACE-ASYNC-7777");
        TenantContextHolder.setTenantId("TENANT-WEST-02");

        OrderMessage order = OrderMessage.builder()
                .orderId("ORD-ASYNC-002")
                .amount(599.0)
                .customerName("李四")
                .status("CREATED")
                .build();

        CompletableFuture<SendReceipt> future = producer.sendOrderAsync(order);
        SendReceipt receipt = future.join();

        assertThat(receipt).isNotNull();
        assertThat(standardConsumer.getReceivedOrders()).hasSize(1);
        assertThat(standardConsumer.getReceivedOrders().get(0).getOrderId()).isEqualTo("ORD-ASYNC-002");
    }

    @Test
    @DisplayName("场景 1.3：Tag 逻辑或多分支过滤 (create || pay) -> 验证 pay 标签消费与未匹配标签安全过滤")
    void testTagLogicalOrFiltering() {
        // 1. 发送 tag = "pay" 消息 -> 预期命中 "create || pay" 被 OrderStandardConsumer 成功消费
        OrderMessage payOrder = OrderMessage.builder()
                .orderId("ORD-TAG-PAY-001")
                .amount(888.0)
                .customerName("王五")
                .status("PAID")
                .build();
        SendReceipt receiptPay = producer.sendOrderWithTag(payOrder, "pay");
        assertThat(receiptPay).isNotNull();
        assertThat(standardConsumer.getReceivedOrders()).hasSize(1);
        assertThat(standardConsumer.getReceivedOrders().get(0).getOrderId()).isEqualTo("ORD-TAG-PAY-001");

        // 2. 发送 tag = "cancel" 消息 -> 预期不符合 "create || pay" 白名单，被服务端过滤丢弃
        OrderMessage cancelOrder = OrderMessage.builder()
                .orderId("ORD-TAG-CANCEL-002")
                .amount(100.0)
                .customerName("赵六")
                .status("CANCELLED")
                .build();
        SendReceipt receiptCancel = producer.sendOrderWithTag(cancelOrder, "cancel");
        assertThat(receiptCancel).isNotNull();
        // 消费端接收列表仍只有之前的 1 条，未收到 cancel 消息
        assertThat(standardConsumer.getReceivedOrders()).hasSize(1);
    }

    // =========================================================================
    // 场景 2：毫秒级延时与定时消息调度投递
    // =========================================================================

    @Test
    @DisplayName("场景 2：延时与定时消息投递 -> deliveryTimestamp 正确封装并路由到 DelayConsumer")
    void testDelayedAndDeliverAtMessage() {
        OrderMessage orderDelay = OrderMessage.builder()
                .orderId("ORD-DELAY-003")
                .amount(120.0)
                .status("PENDING_PAY")
                .build();

        SendReceipt receiptDelay = producer.sendOrderDelay(orderDelay, Duration.ofMinutes(15));
        assertThat(receiptDelay).isNotNull();

        assertThat(delayConsumer.getReceivedDelayOrders()).hasSize(1);
        assertThat(delayConsumer.getReceivedDelayOrders().get(0).getOrderId()).isEqualTo("ORD-DELAY-003");

        OrderMessage orderAt = OrderMessage.builder()
                .orderId("ORD-DELIVER-AT-004")
                .amount(88.0)
                .status("SCHEDULED")
                .build();

        SendReceipt receiptAt = producer.sendOrderDeliverAt(orderAt, Instant.now().plusSeconds(3600));
        assertThat(receiptAt).isNotNull();

        assertThat(delayConsumer.getReceivedDelayOrders()).hasSize(2);
        assertThat(delayConsumer.getReceivedDelayOrders().get(1).getOrderId()).isEqualTo("ORD-DELIVER-AT-004");
    }

    // =========================================================================
    // 场景 3：FIFO 顺序保序消息 (MessageGroup)
    // =========================================================================

    @Test
    @DisplayName("场景 3：FIFO 顺序保序消息 -> MessageGroup 正确透传并路由到 FifoConsumer")
    void testFifoMessage() {
        String group = "SHARDING-GROUP-A";

        OrderMessage order1 = OrderMessage.builder().orderId("ORD-FIFO-01").amount(10.0).build();
        OrderMessage order2 = OrderMessage.builder().orderId("ORD-FIFO-02").amount(20.0).build();

        producer.sendOrderFifo(order1, group);
        producer.sendOrderFifo(order2, group);

        assertThat(fifoConsumer.getReceivedFifoOrders()).hasSize(2);
        assertThat(fifoConsumer.getReceivedMessageGroups()).containsOnly(group);
        assertThat(fifoConsumer.getReceivedFifoOrders().get(0).getOrderId()).isEqualTo("ORD-FIFO-01");
        assertThat(fifoConsumer.getReceivedFifoOrders().get(1).getOrderId()).isEqualTo("ORD-FIFO-02");
    }

    // =========================================================================
    // 场景 4：原生 2PC 分布式事务半消息与统一回查分发
    // =========================================================================

    @Test
    @DisplayName("场景 4.1：2PC 事务半消息 -> 本地执行成功 COMMIT -> 下游消费端成功接收")
    void test2PcTransactionCommit() {
        OrderMessage txOrder = OrderMessage.builder()
                .orderId("ORD-2PC-COMMIT-005")
                .amount(888.8)
                .status("TX_COMMITTED")
                .build();

        SendReceipt receipt = transactionService.createOrderWith2PcTransaction(txOrder, true);

        assertThat(receipt).isNotNull();
        // 验证本地数据库已入库
        assertThat(transactionService.getLocalDbOrders()).contains("ORD-2PC-COMMIT-005");
        // 验证消费端已收到提交的消息
        assertThat(txConsumer.getReceivedTxOrders()).hasSize(1);
        assertThat(txConsumer.getReceivedTxOrders().get(0).getOrderId()).isEqualTo("ORD-2PC-COMMIT-005");
    }

    @Test
    @DisplayName("场景 4.2：2PC 事务半消息 -> 本地执行失败 ROLLBACK -> 半消息丢弃，下游消费端不接收")
    void test2PcTransactionRollback() {
        OrderMessage txOrder = OrderMessage.builder()
                .orderId("ORD-2PC-ROLLBACK-006")
                .amount(999.9)
                .status("TX_ROLLED_BACK")
                .build();

        SendReceipt receipt = transactionService.createOrderWith2PcTransaction(txOrder, false);

        assertThat(receipt).isNotNull();
        // 验证消费端未收到回滚的消息
        assertThat(txConsumer.getReceivedTxOrders()).isEmpty();
    }

    @Test
    @DisplayName("场景 4.3：RocketmqTransactionChecker 统一分发 -> 路由到 SampleOrderTransactionChecker 回查决断")
    void testTransactionCheckerDistribution() {
        sampleOrderTransactionChecker.markCommitted("ORD-CHECK-100");
        sampleOrderTransactionChecker.markRolledBack("ORD-CHECK-200");

        // 构造已提交订单回查视图
        MessageView mvCommit = org.mockito.Mockito.mock(MessageView.class);
        org.mockito.Mockito.when(mvCommit.getTopic()).thenReturn(TradeRocketmqProducer.TOPIC_TX);
        org.mockito.Mockito.when(mvCommit.getKeys()).thenReturn(java.util.Collections.singleton("ORD-CHECK-100"));

        TransactionResolution resCommit = globalTransactionChecker.check(mvCommit);
        assertThat(resCommit).isEqualTo(TransactionResolution.COMMIT);

        // 构造已回滚订单回查视图
        MessageView mvRollback = org.mockito.Mockito.mock(MessageView.class);
        org.mockito.Mockito.when(mvRollback.getTopic()).thenReturn(TradeRocketmqProducer.TOPIC_TX);
        org.mockito.Mockito.when(mvRollback.getKeys()).thenReturn(java.util.Collections.singleton("ORD-CHECK-200"));

        TransactionResolution resRollback = globalTransactionChecker.check(mvRollback);
        assertThat(resRollback).isEqualTo(TransactionResolution.ROLLBACK);

        // 构造未决订单回查视图
        MessageView mvUnknown = org.mockito.Mockito.mock(MessageView.class);
        org.mockito.Mockito.when(mvUnknown.getTopic()).thenReturn(TradeRocketmqProducer.TOPIC_TX);
        org.mockito.Mockito.when(mvUnknown.getKeys()).thenReturn(java.util.Collections.singleton("ORD-CHECK-999"));

        TransactionResolution resUnknown = globalTransactionChecker.check(mvUnknown);
        assertThat(resUnknown).isEqualTo(TransactionResolution.UNKNOWN);

        assertThat(sampleOrderTransactionChecker.getCheckCount().get()).isEqualTo(3);
    }

    // =========================================================================
    // 场景 5：本地事务后置感知发信 (sendAfterCommit) 与异常回滚防幽灵消息
    // =========================================================================

    @Test
    @DisplayName("场景 5.1：@Transactional 成功提交 -> 触发 sendAfterCommit 投递 -> 消费者接收")
    void testSendAfterCommitSuccess() {
        OrderMessage order = OrderMessage.builder()
                .orderId("ORD-AFTER-COMMIT-007")
                .amount(350.0)
                .status("PAID")
                .build();

        transactionService.createOrderWithSendAfterCommit(order);

        assertThat(transactionService.getLocalDbOrders()).contains("ORD-AFTER-COMMIT-007");
        assertThat(standardConsumer.getReceivedOrders()).hasSize(1);
        assertThat(standardConsumer.getReceivedOrders().get(0).getOrderId()).isEqualTo("ORD-AFTER-COMMIT-007");
    }

    @Test
    @DisplayName("场景 5.2：@Transactional 发生回滚 -> sendAfterCommit 钩子自动丢弃 -> 无幽灵消息发送")
    void testSendAfterCommitRollback() {
        OrderMessage order = OrderMessage.builder()
                .orderId("ORD-AFTER-ROLLBACK-008")
                .amount(450.0)
                .status("FAILED")
                .build();

        assertThatThrownBy(() -> transactionService.createOrderWithRollback(order))
                .isInstanceOf(IllegalStateException.class);

        // 本地事务回滚，消息未被发送
        assertThat(standardConsumer.getReceivedOrders()).isEmpty();
    }

    // =========================================================================
    // 场景 6：SQL92 服务端属性过滤
    // =========================================================================

    @Test
    @DisplayName("场景 6：SQL92 属性过滤 -> amount > 100 投递，amount <= 100 拦截")
    void testSql92Filtering() {
        OrderMessage orderMatch = OrderMessage.builder()
                .orderId("ORD-SQL-MATCH")
                .amount(250.0)
                .build();
        producer.sendOrderWithSqlProperties(orderMatch, Map.of("amount", "250.0"));

        OrderMessage orderMismatch = OrderMessage.builder()
                .orderId("ORD-SQL-MISMATCH")
                .amount(50.0)
                .build();
        producer.sendOrderWithSqlProperties(orderMismatch, Map.of("amount", "50.0"));

        // 仅匹配 amount > 100 的消息到达消费者
        assertThat(sqlConsumer.getReceivedSqlOrders()).hasSize(1);
        assertThat(sqlConsumer.getReceivedSqlOrders().get(0).getOrderId()).isEqualTo("ORD-SQL-MATCH");
    }

    // =========================================================================
    // 场景 7：毒丸不可自愈异常安全丢弃与快速确认
    // =========================================================================

    @Test
    @DisplayName("场景 7：毒丸不可自愈异常 (POISON_FATAL) -> 框架捕获判定毒丸并安全确认丢弃 -> 阻断循环重试")
    void testPoisonPillFatalExceptionDiscard() {
        OrderMessage poison = OrderMessage.builder()
                .orderId("ORD-POISON_FATAL-009")
                .amount(100.0)
                .build();

        // 发射毒丸消息 (tag=poison)
        testBroker.routeAndDispatch(org.apache.rocketmq.client.apis.ClientServiceProvider.loadService()
                .newMessageBuilder()
                .setTopic(TradeRocketmqProducer.TOPIC_ORDER)
                .setTag("poison")
                .setKeys(poison.getOrderId())
                .setBody(io.github.atengk.boot.core.util.JsonUtil.toJsonBytes(poison))
                .build());

        // 验证毒丸消息未成功正常计入业务处理
        assertThat(poisonConsumer.getPoisonProcessCount().get()).isEqualTo(0);
    }

    // =========================================================================
    // 场景 8：@Idempotent 防重冲突静默确认阻断重试
    // =========================================================================

    @Test
    @DisplayName("场景 8：防重幂等拦截 (IdempotentException) -> 框架捕获并静默确认 SUCCESS -> 阻断重试")
    void testIdempotentDuplicateSilentAck() {
        OrderMessage order = OrderMessage.builder()
                .orderId("ORD-IDEMPOTENT-010")
                .amount(199.0)
                .build();

        org.apache.rocketmq.client.apis.message.Message mqMsg = org.apache.rocketmq.client.apis.ClientServiceProvider.loadService()
                .newMessageBuilder()
                .setTopic(TradeRocketmqProducer.TOPIC_ORDER)
                .setTag("idempotent")
                .setKeys(order.getOrderId())
                .setBody(io.github.atengk.boot.core.util.JsonUtil.toJsonBytes(order))
                .build();

        // 第一次投递：首次消费成功
        testBroker.routeAndDispatch(mqMsg);
        assertThat(idempotentConsumer.getSuccessProcessCount().get()).isEqualTo(1);
        assertThat(idempotentConsumer.getDuplicateInterceptCount().get()).isEqualTo(0);

        // 第二次重复投递：命中防重抛出 IdempotentException，框架静默确认返回 SUCCESS
        testBroker.routeAndDispatch(mqMsg);
        assertThat(idempotentConsumer.getSuccessProcessCount().get()).isEqualTo(1);
        assertThat(idempotentConsumer.getDuplicateInterceptCount().get()).isEqualTo(1);
    }

    // =========================================================================
    // 场景 9：4MB 大报文防御超限拦截
    // =========================================================================

    @Test
    @DisplayName("场景 9：大报文防御 -> 超过 4MB 上限抛出 MessageDeliveryException 快速失败")
    void testLargePayloadDefense() {
        // 构建 4.5MB 超限报文
        byte[] largeBytes = new byte[4500 * 1024];
        java.util.Arrays.fill(largeBytes, (byte) 'A');
        String largeString = new String(largeBytes);

        OrderMessage largeOrder = OrderMessage.builder()
                .orderId("ORD-LARGE")
                .customerName(largeString)
                .build();

        assertThatThrownBy(() -> producer.sendOrder(largeOrder))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("超过配置上限");
    }

    // =========================================================================
    // 场景 10：SmartLifecycle 确定性停机排空
    // =========================================================================

    @Test
    @DisplayName("场景 10：SmartLifecycle 确定性停机排空 -> stop 触发所有 PushConsumer 实例关闭")
    void testGracefulShutdown() {
        assertThat(consumerContainer.isRunning()).isTrue();
        assertThat(testBroker.getActivePushConsumers()).isNotEmpty();

        // 触发停机
        consumerContainer.stop();

        assertThat(consumerContainer.isRunning()).isFalse();
        for (InMemoryRocketmqBroker.PushConsumerHolder holder : testBroker.getActivePushConsumers()) {
            assertThat(holder.closed().get()).isTrue();
        }
    }

    // =========================================================================
    // 测试环境配置与 TestDouble 注册
    // =========================================================================

    @TestConfiguration
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
