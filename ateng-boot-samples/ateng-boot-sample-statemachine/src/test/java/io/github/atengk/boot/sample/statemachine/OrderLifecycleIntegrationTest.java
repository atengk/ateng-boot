package io.github.atengk.boot.sample.statemachine;

import io.github.atengk.boot.sample.statemachine.config.OrderStateMachineConfig;
import io.github.atengk.boot.sample.statemachine.constant.OrderEvent;
import io.github.atengk.boot.sample.statemachine.constant.OrderState;
import io.github.atengk.boot.sample.statemachine.context.OrderContext;
import io.github.atengk.boot.sample.statemachine.entity.OrderDO;
import io.github.atengk.boot.sample.statemachine.listener.OrderTransitionAuditLogger;
import io.github.atengk.boot.sample.statemachine.mapper.OrderMapper;
import io.github.atengk.boot.sample.statemachine.service.OrderLifecycleScenarioService;
import io.github.atengk.boot.sample.statemachine.service.OrderProbeScenarioService;
import io.github.atengk.boot.sample.statemachine.service.OrderTimeoutBatchScenarioService;
import io.github.atengk.boot.sample.statemachine.service.PaymentCallbackScenarioService;
import io.github.atengk.boot.statemachine.core.StateMachine;
import io.github.atengk.boot.statemachine.core.StateMachineFactory;
import io.github.atengk.boot.statemachine.enums.StateMachineResultCode;
import io.github.atengk.boot.statemachine.exception.StateMachineException;
import io.github.atengk.boot.statemachine.model.BatchTransitionResult;
import io.github.atengk.boot.statemachine.model.TransitionAuditEvent;
import io.github.atengk.boot.statemachine.model.TransitionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 电商订单状态机 4 大专职场景服务全流程端到端集成测试
 * <p>基于纯净 Headless 非 Web 架构，在真实 H2 内存库中全真自验</p>
 *
 * @author Ateng
 * @since 2026-11-01
 */
@SpringBootTest
class OrderLifecycleIntegrationTest {

    @Autowired
    private OrderLifecycleScenarioService lifecycleService;

    @Autowired
    private OrderProbeScenarioService probeService;

    @Autowired
    private PaymentCallbackScenarioService paymentCallbackService;

    @Autowired
    private OrderTimeoutBatchScenarioService batchScenarioService;

    @Autowired
    private OrderStateMachineConfig machineConfig;

    @Autowired
    private OrderTransitionAuditLogger auditLogger;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private StateMachine<OrderState, OrderEvent, OrderContext> orderStateMachine;

    @BeforeEach
    void setUp() {
        machineConfig.clearRecords();
        auditLogger.clear();
    }

    @AfterEach
    void tearDown() {
        machineConfig.clearRecords();
        auditLogger.clear();
    }

    @Test
    @DisplayName("双通道获取状态机：Spring 依赖注入与 StateMachineFactory 工厂内省保持一致")
    void testStateMachineFactoryLookup() {
        StateMachine<OrderState, OrderEvent, OrderContext> found = StateMachineFactory.get(OrderStateMachineConfig.MACHINE_ID);
        assertThat(found).isNotNull();
        assertThat(found.getMachineId()).isEqualTo(OrderStateMachineConfig.MACHINE_ID);
        assertThat(found).isSameAs(orderStateMachine);

        assertThat(StateMachineFactory.contains(OrderStateMachineConfig.MACHINE_ID)).isTrue();
        assertThat(StateMachineFactory.getAllMachineIds()).contains(OrderStateMachineConfig.MACHINE_ID);
        assertThat(StateMachineFactory.size()).isGreaterThanOrEqualTo(1);
    }

    // ==========================================
    // 场景一：OrderLifecycleScenarioService 验证
    // ==========================================

    @Test
    @DisplayName("场景一：端到端全生命周期主干流转 (WAIT_PAY -> PAID -> WAIT_DELIVER -> DELIVERING -> DELIVERED -> FINISHED)")
    void testHappyPathFullLifecycle() {
        String sn = "ORD-HAPPY-" + UUID.randomUUID().toString().substring(0, 8);
        OrderDO created = lifecycleService.createOrder(sn, new BigDecimal("199.99"));
        Long id = created.getId();
        assertThat(created.getStatus()).isEqualTo(OrderState.WAIT_PAY);

        // 1. 买家付款：WAIT_PAY -> PAID
        TransitionResult<OrderState> payResult = lifecycleService.pay(id);
        assertThat(payResult.isSuccess()).isTrue();
        assertThat(payResult.getTargetState()).isEqualTo(OrderState.PAID);
        assertThat(lifecycleService.getOrder(id).getStatus()).isEqualTo(OrderState.PAID);

        // 2. Choice 动态路由（低风险放行）：PAID -> WAIT_DELIVER
        TransitionResult<OrderState> auditResult = lifecycleService.auditRisk(id, 20, false);
        assertThat(auditResult.isSuccess()).isTrue();
        assertThat(auditResult.getTargetState()).isEqualTo(OrderState.WAIT_DELIVER);
        assertThat(lifecycleService.getOrder(id).getStatus()).isEqualTo(OrderState.WAIT_DELIVER);

        // 3. 商家发货：WAIT_DELIVER -> DELIVERING
        TransitionResult<OrderState> shipResult = lifecycleService.ship(id, "SF123456789");
        assertThat(shipResult.isSuccess()).isTrue();
        assertThat(shipResult.getTargetState()).isEqualTo(OrderState.DELIVERING);
        assertThat(lifecycleService.getOrder(id).getTrackingNo()).isEqualTo("SF123456789");

        // 4. 买家签收妥投：DELIVERING -> DELIVERED
        TransitionResult<OrderState> deliveryResult = lifecycleService.confirmDelivery(id);
        assertThat(deliveryResult.isSuccess()).isTrue();
        assertThat(deliveryResult.getTargetState()).isEqualTo(OrderState.DELIVERED);

        // 5. 交易完成：DELIVERED -> FINISHED
        TransitionResult<OrderState> finishResult = lifecycleService.finish(id);
        assertThat(finishResult.isSuccess()).isTrue();
        assertThat(finishResult.getTargetState()).isEqualTo(OrderState.FINISHED);
        assertThat(lifecycleService.getOrder(id).getStatus()).isEqualTo(OrderState.FINISHED);
    }

    @Test
    @DisplayName("场景一：内部自跃迁 (DELIVERING 追加物流动态，状态保持不变)")
    void testInternalTransitionKeepsState() {
        String sn = "ORD-INTERNAL-" + UUID.randomUUID().toString().substring(0, 8);
        OrderDO created = lifecycleService.createOrder(sn, new BigDecimal("88.00"));
        Long id = created.getId();

        lifecycleService.pay(id);
        lifecycleService.auditRisk(id, 10, false);
        lifecycleService.ship(id, "JD987654321");

        OrderDO before = lifecycleService.getOrder(id);
        assertThat(before.getStatus()).isEqualTo(OrderState.DELIVERING);

        // 首次内部自跃迁
        TransitionResult<OrderState> track1 = lifecycleService.addTracking(id, "JD987654321", "快件已到达【北京分拨中心】");
        assertThat(track1.isSuccess()).isTrue();
        assertThat(track1.getTargetState()).isEqualTo(OrderState.DELIVERING);

        OrderDO after1 = lifecycleService.getOrder(id);
        assertThat(after1.getStatus()).isEqualTo(OrderState.DELIVERING);
        assertThat(after1.getTrackingRemark()).contains("北京分拨中心");

        // 二次内部自跃迁
        TransitionResult<OrderState> track2 = lifecycleService.addTracking(id, "JD987654321", "快件正在派送中");
        assertThat(track2.isSuccess()).isTrue();
        assertThat(track2.getTargetState()).isEqualTo(OrderState.DELIVERING);

        OrderDO after2 = lifecycleService.getOrder(id);
        assertThat(after2.getStatus()).isEqualTo(OrderState.DELIVERING);
        assertThat(after2.getTrackingRemark()).contains("快件正在派送中");
    }

    @Test
    @DisplayName("场景一：Choice 动态分支与事务回滚 (高风险流向退款，模拟 Action 异常触发事务原子回滚)")
    void testChoiceTransitionBranchingAndRollback() {
        // 分支 A: 高危风控 (>= 80) 流向 REFUNDING
        String sn1 = "ORD-RISK-HIGH-" + UUID.randomUUID().toString().substring(0, 8);
        OrderDO o1 = lifecycleService.createOrder(sn1, new BigDecimal("9999.00"));
        lifecycleService.pay(o1.getId());

        TransitionResult<OrderState> highRiskResult = lifecycleService.auditRisk(o1.getId(), 95, false);
        assertThat(highRiskResult.isSuccess()).isTrue();
        assertThat(highRiskResult.getTargetState()).isEqualTo(OrderState.REFUNDING);
        assertThat(lifecycleService.getOrder(o1.getId()).getStatus()).isEqualTo(OrderState.REFUNDING);

        // 分支 B: 模拟 Action 执行异常，验证本地事务原子回滚
        String sn2 = "ORD-ROLLBACK-" + UUID.randomUUID().toString().substring(0, 8);
        OrderDO o2 = lifecycleService.createOrder(sn2, new BigDecimal("120.00"));
        lifecycleService.pay(o2.getId());
        assertThat(lifecycleService.getOrder(o2.getId()).getStatus()).isEqualTo(OrderState.PAID);

        assertThatThrownBy(() -> lifecycleService.auditRisk(o2.getId(), 10, true))
                .isInstanceOf(StateMachineException.class)
                .hasMessageContaining("模拟 Action 执行业务异常");

        // 验证数据库状态事务原子回滚，依然保持为 PAID
        OrderDO currentInDb = orderMapper.selectById(o2.getId());
        assertThat(currentInDb.getStatus()).isEqualTo(OrderState.PAID);
    }

    @Test
    @DisplayName("场景一：多源汇聚跃迁 (WAIT_PAY / PAID / WAIT_DELIVER 均可通过 CANCEL 汇聚到 CLOSED)")
    void testMultiFromTransitionCancel() {
        // 1. WAIT_PAY -> CLOSED
        OrderDO o1 = lifecycleService.createOrder("ORD-C1-" + UUID.randomUUID().toString().substring(0, 6), BigDecimal.TEN);
        TransitionResult<OrderState> r1 = lifecycleService.cancel(o1.getId(), "用户主动取消");
        assertThat(r1.isSuccess()).isTrue();
        assertThat(r1.getTargetState()).isEqualTo(OrderState.CLOSED);

        // 2. PAID -> CLOSED
        OrderDO o2 = lifecycleService.createOrder("ORD-C2-" + UUID.randomUUID().toString().substring(0, 6), BigDecimal.TEN);
        lifecycleService.pay(o2.getId());
        TransitionResult<OrderState> r2 = lifecycleService.cancel(o2.getId(), "缺货取消");
        assertThat(r2.isSuccess()).isTrue();
        assertThat(r2.getTargetState()).isEqualTo(OrderState.CLOSED);

        // 3. WAIT_DELIVER -> CLOSED
        OrderDO o3 = lifecycleService.createOrder("ORD-C3-" + UUID.randomUUID().toString().substring(0, 6), BigDecimal.TEN);
        lifecycleService.pay(o3.getId());
        lifecycleService.auditRisk(o3.getId(), 10, false);
        TransitionResult<OrderState> r3 = lifecycleService.cancel(o3.getId(), "风控拦截后协商取消");
        assertThat(r3.isSuccess()).isTrue();
        assertThat(r3.getTargetState()).isEqualTo(OrderState.CLOSED);
    }

    @Test
    @DisplayName("场景一：状态节点 Entry/Exit 生命周期动作触发与 UML 语义不变量")
    void testStateNodeLifecycleEntryAndExitActions() {
        String sn = "ORD-LIFECYCLE-" + UUID.randomUUID().toString().substring(0, 8);
        OrderDO order = lifecycleService.createOrder(sn, new BigDecimal("300.00"));
        Long id = order.getId();

        lifecycleService.pay(id);
        // 流转至 WAIT_DELIVER，触发 WAIT_DELIVER onEntry
        lifecycleService.auditRisk(id, 20, false);

        List<String> records = machineConfig.getLifecycleRecords();
        assertThat(records).contains("ENTRY:WAIT_DELIVER:" + sn);

        // 从 WAIT_DELIVER 发货流向 DELIVERING，触发 WAIT_DELIVER onExit
        lifecycleService.ship(id, "SF888888");
        assertThat(machineConfig.getLifecycleRecords()).contains("EXIT:WAIT_DELIVER:" + sn);

        // 取消一个订单流向 CLOSED，触发 CLOSED onEntry
        String cancelSn = "ORD-CANCEL-EXIT-" + UUID.randomUUID().toString().substring(0, 8);
        OrderDO cancelOrder = lifecycleService.createOrder(cancelSn, BigDecimal.TEN);
        lifecycleService.cancel(cancelOrder.getId(), "直接取消");
        assertThat(machineConfig.getLifecycleRecords()).contains("ENTRY:CLOSED:" + cancelSn);
    }

    // ==========================================
    // 场景二：OrderProbeScenarioService 验证
    // ==========================================

    @Test
    @DisplayName("场景二：零副作用只读预检探测 (canFire / verifyTransition / getAvailableEvents / evaluateButtonPermissions)")
    void testOrderProbeScenarioService() {
        String sn = "ORD-PROBE-" + UUID.randomUUID().toString().substring(0, 8);
        OrderDO order = lifecycleService.createOrder(sn, new BigDecimal("250.00"));
        Long id = order.getId();

        // 1. 待支付状态探测
        assertThat(probeService.canPay(id)).isTrue();
        assertThat(probeService.canCancel(id)).isTrue();
        assertThat(probeService.canShip(id, "SF123")).isFalse(); // 待支付不能发货

        // 2. 预测下一状态与显式 verifyTransition 契约验证
        OrderContext payCtx = OrderContext.builder().orderId(id).amount(order.getAmount()).build();
        TransitionResult<OrderState> previewResult = probeService.previewNextState(id, OrderEvent.PAY, payCtx);
        assertThat(previewResult.isAccepted()).isTrue();
        assertThat(previewResult.getTargetState()).isEqualTo(OrderState.PAID);

        TransitionResult<OrderState> verifyResult = probeService.verifyTransition(id, OrderEvent.PAY, payCtx);
        assertThat(verifyResult.isAccepted()).isTrue();
        assertThat(verifyResult.getTargetState()).isEqualTo(OrderState.PAID);
        assertThat(verifyResult.getSourceState()).isEqualTo(OrderState.WAIT_PAY);

        // 3. 获取可用事件列表
        List<OrderEvent> availableEvents = probeService.getAvailableEvents(id, payCtx);
        assertThat(availableEvents).contains(OrderEvent.PAY, OrderEvent.CANCEL);
        assertThat(availableEvents).doesNotContain(OrderEvent.SHIP, OrderEvent.CONFIRM_DELIVERY);

        // 4. 按钮权限评估视图
        Map<String, Boolean> permissions = probeService.evaluateButtonPermissions(id);
        assertThat(permissions.get("btnPay")).isTrue();
        assertThat(permissions.get("btnCancel")).isTrue();
        assertThat(permissions.get("btnShip")).isFalse();
        assertThat(permissions.get("btnConfirmDelivery")).isFalse();

        // 5. 校验零副作用不变量：订单在数据库中依然处于 WAIT_PAY 且版本号未改变
        OrderDO afterProbe = lifecycleService.getOrder(id);
        assertThat(afterProbe.getStatus()).isEqualTo(OrderState.WAIT_PAY);
        assertThat(afterProbe.getVersion()).isEqualTo(0);
    }

    // ==========================================
    // 场景三：PaymentCallbackScenarioService 验证
    // ==========================================

    @Test
    @DisplayName("场景三：支付异步回调 CAS 并发防重与声明式幂等放行 (idempotent)")
    void testPaymentCallbackCasAndIdempotency() throws InterruptedException {
        String sn = "ORD-PAY-CB-" + UUID.randomUUID().toString().substring(0, 8);
        OrderDO order = lifecycleService.createOrder(sn, new BigDecimal("500.00"));
        Long id = order.getId();

        // 1. 并发两个支付回调，仅有 1 个 CAS 成功，另一个触发并发冲突或幂等
        int threadCount = 2;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            new Thread(() -> {
                try {
                    startLatch.await();
                    paymentCallbackService.handlePaymentCallback(id, new BigDecimal("500.00"), "TX-" + index);
                    successCount.incrementAndGet();
                } catch (StateMachineException e) {
                    if (e.getResultCode().getCode().equals(StateMachineResultCode.CONCURRENT_CAS_CONFLICT.getCode())) {
                        conflictCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        endLatch.await();

        // 确保状态已变为 PAID
        assertThat(lifecycleService.getOrder(id).getStatus()).isEqualTo(OrderState.PAID);

        // 2. 声明式幂等测试：后续重复回调到达时，当前已是 PAID，由于配置了 IdempotentStrategy.ACCEPT，平滑放行无报错
        TransitionResult<OrderState> repeatResult = paymentCallbackService.handlePaymentCallback(id, new BigDecimal("500.00"), "TX-REPEAT");
        assertThat(repeatResult.isSuccess()).isTrue();
        assertThat(repeatResult.getTargetState()).isEqualTo(OrderState.PAID);
    }

    @Test
    @DisplayName("场景三：Spring 本地事务提交后 (performAfterCommit) 外部不可逆通知调度自验")
    void testPaymentCallbackPostCommitNotification() {
        String sn = "ORD-POSTCOMMIT-" + UUID.randomUUID().toString().substring(0, 8);
        OrderDO order = lifecycleService.createOrder(sn, new BigDecimal("666.00"));
        Long id = order.getId();

        // 执行支付回调
        paymentCallbackService.handlePaymentCallback(id, new BigDecimal("666.00"), "TX-POST-123");

        // 验证 performAfterCommit 钩子已被触发，成功记录外部通知日志
        List<String> postCommitRecords = machineConfig.getPostCommitRecords();
        assertThat(postCommitRecords).contains("POST_COMMIT_NOTIFY:" + sn);
    }

    // ==========================================
    // 场景四：OrderTimeoutBatchScenarioService 验证
    // ==========================================

    @Test
    @DisplayName("场景四：大批量超时切片关单引擎 (fireEventBatch 切片处理与结构化聚合结果)")
    void testBatchCancelExpiredOrders() {
        // 创建 25 笔超时未支付订单
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            OrderDO o = lifecycleService.createOrder("ORD-BATCH-" + i + "-" + UUID.randomUUID().toString().substring(0, 4),
                    new BigDecimal("10.00"));
            orderIds.add(o.getId());
        }

        // 以批次大小 10 进行切片批量关单
        BatchTransitionResult<OrderState, OrderDO> batchResult = batchScenarioService.batchCancelExpiredOrders(
                orderIds, "超时自动取消", 10);

        assertThat(batchResult).isNotNull();
        assertThat(batchResult.getTotalCount()).isEqualTo(25);
        assertThat(batchResult.getSuccessCount()).isEqualTo(25);
        assertThat(batchResult.getFailureCount()).isEqualTo(0);
        assertThat(batchResult.isAllSuccess()).isTrue();

        // 验证数据库中 25 笔订单状态均已变更为 CLOSED
        List<OrderDO> closedOrders = orderMapper.selectBatchIds(orderIds);
        assertThat(closedOrders).allMatch(o -> o.getStatus() == OrderState.CLOSED);
    }

    @Test
    @DisplayName("场景四：多源混合状态订单 (WAIT_PAY / PAID / WAIT_DELIVER) 批量取消与精准 CAS 更新")
    void testBatchCancelMultiFromStatusOrders() {
        // 创建 3 笔不同状态的前置订单
        OrderDO o1 = lifecycleService.createOrder("ORD-MULTI-1-" + UUID.randomUUID().toString().substring(0, 4), BigDecimal.TEN);
        OrderDO o2 = lifecycleService.createOrder("ORD-MULTI-2-" + UUID.randomUUID().toString().substring(0, 4), BigDecimal.TEN);
        OrderDO o3 = lifecycleService.createOrder("ORD-MULTI-3-" + UUID.randomUUID().toString().substring(0, 4), BigDecimal.TEN);

        // o1: 处于 WAIT_PAY
        // o2: 流转至 PAID
        lifecycleService.pay(o2.getId());
        // o3: 流转至 WAIT_DELIVER
        lifecycleService.pay(o3.getId());
        lifecycleService.auditRisk(o3.getId(), 10, false);

        List<Long> batchIds = List.of(o1.getId(), o2.getId(), o3.getId());
        BatchTransitionResult<OrderState, OrderDO> batchResult = batchScenarioService.batchCancelExpiredOrders(
                batchIds, "多源混合状态批量关单", 5);

        assertThat(batchResult.getTotalCount()).isEqualTo(3);
        assertThat(batchResult.getSuccessCount()).isEqualTo(3);
        assertThat(batchResult.isAllSuccess()).isTrue();

        // 验证数据库中 3 笔订单无论原状态是什么，均原子 CAS 更新为 CLOSED
        List<OrderDO> closedOrders = orderMapper.selectBatchIds(batchIds);
        assertThat(closedOrders).hasSize(3);
        assertThat(closedOrders).allMatch(o -> o.getStatus() == OrderState.CLOSED);
    }

    @Test
    @DisplayName("场景四：批量切片流转部分成功与异常容错聚合自验")
    void testBatchPartialFailureResilience() {
        // 创建 3 笔订单：2 笔 WAIT_PAY，1 笔已流转至 FINISHED（终态无法执行 CANCEL）
        OrderDO o1 = lifecycleService.createOrder("ORD-PF-1-" + UUID.randomUUID().toString().substring(0, 4), BigDecimal.ONE);
        OrderDO o2 = lifecycleService.createOrder("ORD-PF-2-" + UUID.randomUUID().toString().substring(0, 4), BigDecimal.ONE);
        OrderDO o3 = lifecycleService.createOrder("ORD-PF-3-" + UUID.randomUUID().toString().substring(0, 4), BigDecimal.ONE);

        // 将 o3 直接流转至 FINISHED
        lifecycleService.pay(o3.getId());
        lifecycleService.auditRisk(o3.getId(), 20, false);
        lifecycleService.ship(o3.getId(), "SF-PF-3");
        lifecycleService.confirmDelivery(o3.getId());
        lifecycleService.finish(o3.getId());

        List<Long> batchIds = List.of(o1.getId(), o2.getId(), o3.getId());
        BatchTransitionResult<OrderState, OrderDO> batchResult = batchScenarioService.batchCancelExpiredOrders(
                batchIds, "部分失败批次测试", 2);

        assertThat(batchResult.getTotalCount()).isEqualTo(3);
        assertThat(batchResult.getSuccessCount()).isEqualTo(2);
        assertThat(batchResult.getFailureCount()).isEqualTo(1);
        assertThat(batchResult.isAllSuccess()).isFalse();

        // 失败详情精准包含 o3 且记录错误码 TRANSITION_NOT_FOUND
        assertThat(batchResult.getFailures()).hasSize(1);
        OrderDO failedEntity = batchResult.getFailureEntities().get(0);
        assertThat(failedEntity.getId()).isEqualTo(o3.getId());
        assertThat(batchResult.getFailures().get(failedEntity).getCode()).isEqualTo(StateMachineResultCode.TRANSITION_NOT_FOUND.getCode());
    }

    // ==========================================
    // 轨迹审计与图例导出验证
    // ==========================================

    @Test
    @DisplayName("专职审计监听器 SPI 捕获流转轨迹 (OrderTransitionAuditLogger)")
    void testAuditListenerCapture() {
        String sn = "ORD-AUDIT-" + UUID.randomUUID().toString().substring(0, 8);
        OrderDO order = lifecycleService.createOrder(sn, new BigDecimal("188.00"));
        Long id = order.getId();

        lifecycleService.pay(id);

        List<TransitionAuditEvent<OrderState, OrderEvent, OrderContext>> events = auditLogger.getAuditEvents();
        assertThat(events).isNotEmpty();

        TransitionAuditEvent<OrderState, OrderEvent, OrderContext> payEvent = events.stream()
                .filter(e -> e.getEvent() == OrderEvent.PAY)
                .findFirst()
                .orElse(null);
        assertThat(payEvent).isNotNull();
        assertThat(payEvent.getMachineId()).isEqualTo(OrderStateMachineConfig.MACHINE_ID);
        assertThat(payEvent.getFromState()).isEqualTo(OrderState.WAIT_PAY);
        assertThat(payEvent.getToState()).isEqualTo(OrderState.PAID);
        assertThat(payEvent.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("状态机拓扑可视化导出：Mermaid 与 PlantUML 语法图例生成自验")
    void testTopologyExportMermaidAndPlantUml() {
        String mermaid = lifecycleService.exportMermaidDiagram();
        assertThat(mermaid)
                .contains("stateDiagram-v2")
                .contains("WAIT_PAY --> PAID : PAY")
                .contains("DELIVERING --> DELIVERING : ADD_TRACKING")
                .contains("DELIVERED --> FINISHED : FINISH");

        String plantUml = lifecycleService.exportPlantUmlDiagram();
        assertThat(plantUml)
                .startsWith("@startuml")
                .endsWith("@enduml")
                .contains("WAIT_PAY --> PAID : PAY")
                .contains("DELIVERING --> DELIVERING : ADD_TRACKING");
    }
}
