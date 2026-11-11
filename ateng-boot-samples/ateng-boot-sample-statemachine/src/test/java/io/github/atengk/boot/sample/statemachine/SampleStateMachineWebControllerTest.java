package io.github.atengk.boot.sample.statemachine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.statemachine.config.OrderStateMachineConfig;
import io.github.atengk.boot.sample.statemachine.constant.OrderEvent;
import io.github.atengk.boot.sample.statemachine.constant.OrderState;
import io.github.atengk.boot.sample.statemachine.context.OrderContext;
import io.github.atengk.boot.statemachine.core.StateMachine;
import io.github.atengk.boot.statemachine.core.StateMachineFactory;
import io.github.atengk.boot.sample.statemachine.dto.OrderAddTrackingRequest;
import io.github.atengk.boot.sample.statemachine.dto.OrderBatchCancelRequest;
import io.github.atengk.boot.sample.statemachine.dto.OrderCreateRequest;
import io.github.atengk.boot.sample.statemachine.dto.OrderPaymentCallbackRequest;
import io.github.atengk.boot.sample.statemachine.dto.OrderProbeRequest;
import io.github.atengk.boot.sample.statemachine.dto.OrderRiskAuditRequest;
import io.github.atengk.boot.sample.statemachine.dto.OrderShipRequest;
import io.github.atengk.boot.sample.statemachine.entity.OrderDO;
import io.github.atengk.boot.sample.statemachine.listener.OrderTransitionAuditLogger;
import io.github.atengk.boot.sample.statemachine.service.OrderLifecycleScenarioService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 状态机样例工程 5 大领域 Controller 端到端 MockMvc 集成测试套件
 * <p>
 * 遵循最高测试接缝 (The Highest Seam) 原则，全链路验证 Web 边界请求解析、
 * JSR-303 参数校验快速失败、强类型 Result 契约、5 大领域控制器业务逻辑及零实体泄漏。
 *
 * @author Ateng
 * @since 2026-11-11
 */
@SpringBootTest
@AutoConfigureMockMvc
class SampleStateMachineWebControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderLifecycleScenarioService lifecycleService;

    @Autowired
    private OrderStateMachineConfig machineConfig;

    @Autowired
    private OrderTransitionAuditLogger auditLogger;

    @Autowired
    private StateMachine<OrderState, OrderEvent, OrderContext> orderStateMachine;

    @BeforeEach
    void setUp() {
        machineConfig.clearRecords();
        auditLogger.clear();
        StateMachineFactory.registerOrUpdate(orderStateMachine);
    }

    @AfterEach
    void tearDown() {
        machineConfig.clearRecords();
        auditLogger.clear();
    }

    // =========================================================================
    // 1. StateMachineOrderController 实测 (订单生命周期流转)
    // =========================================================================

    @Test
    @DisplayName("OrderController: POST /create 成功创建初始订单")
    void testCreateOrder_Success() throws Exception {
        String sn = "MOCK-ORD-" + UUID.randomUUID().toString().substring(0, 8);
        OrderCreateRequest req = OrderCreateRequest.builder()
                .orderSn(sn)
                .amount(new BigDecimal("199.99"))
                .build();

        mockMvc.perform(post("/sample/statemachine/order/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.orderSn").value(sn))
                .andExpect(jsonPath("$.data.status").value("WAIT_PAY"))
                .andExpect(jsonPath("$.data.amount").value(199.99))
                .andExpect(jsonPath("$.data.version").value(0));
    }

    @Test
    @DisplayName("OrderController: POST /create 参数校验失败快速拦截 (JSR-303 防卫)")
    void testCreateOrder_ValidationFailure() throws Exception {
        OrderCreateRequest req = OrderCreateRequest.builder()
                .orderSn("") // 空单号
                .amount(new BigDecimal("-10.00")) // 负金额
                .build();

        mockMvc.perform(post("/sample/statemachine/order/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("OrderController: GET /{orderId} 查询订单详情")
    void testGetOrder_Success() throws Exception {
        String sn = "MOCK-ORD-GET-" + UUID.randomUUID().toString().substring(0, 8);
        OrderDO order = lifecycleService.createOrder(sn, new BigDecimal("88.00"));

        mockMvc.perform(get("/sample/statemachine/order/" + order.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(order.getId()))
                .andExpect(jsonPath("$.data.orderSn").value(sn))
                .andExpect(jsonPath("$.data.status").value("WAIT_PAY"));
    }

    @Test
    @DisplayName("OrderController: POST /{orderId}/pay 外部主干流转买家付款 (WAIT_PAY -> PAID)")
    void testPay_Success() throws Exception {
        String sn = "MOCK-ORD-PAY-" + UUID.randomUUID().toString().substring(0, 8);
        OrderDO order = lifecycleService.createOrder(sn, new BigDecimal("66.00"));

        mockMvc.perform(post("/sample/statemachine/order/" + order.getId() + "/pay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderId").value(order.getId()))
                .andExpect(jsonPath("$.data.fromState").value("WAIT_PAY"))
                .andExpect(jsonPath("$.data.toState").value("PAID"))
                .andExpect(jsonPath("$.data.accepted").value(true));
    }

    @Test
    @DisplayName("OrderController: POST /risk-audit Choice 分支分流 (低分放行至 WAIT_DELIVER，高分拦截至 REFUNDING)")
    void testRiskAudit_ChoiceBranching() throws Exception {
        // 分支 A: 低风险正常放行
        String sn1 = "MOCK-ORD-RISK-1-" + UUID.randomUUID().toString().substring(0, 8);
        OrderDO order1 = lifecycleService.createOrder(sn1, new BigDecimal("100.00"));
        lifecycleService.pay(order1.getId());

        OrderRiskAuditRequest reqLow = OrderRiskAuditRequest.builder()
                .orderId(order1.getId())
                .riskScore(20)
                .build();

        mockMvc.perform(post("/sample/statemachine/order/risk-audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqLow)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.toState").value("WAIT_DELIVER"));

        // 分支 B: 高风险拦截至退款
        String sn2 = "MOCK-ORD-RISK-2-" + UUID.randomUUID().toString().substring(0, 8);
        OrderDO order2 = lifecycleService.createOrder(sn2, new BigDecimal("200.00"));
        lifecycleService.pay(order2.getId());

        OrderRiskAuditRequest reqHigh = OrderRiskAuditRequest.builder()
                .orderId(order2.getId())
                .riskScore(95)
                .build();

        mockMvc.perform(post("/sample/statemachine/order/risk-audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqHigh)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.toState").value("REFUNDING"));
    }

    @Test
    @DisplayName("OrderController: POST /ship 发货 (WAIT_DELIVER -> DELIVERING) 与 POST /add-tracking 内部自跃迁")
    void testShipAndInternalTracking() throws Exception {
        String sn = "MOCK-ORD-SHIP-" + UUID.randomUUID().toString().substring(0, 8);
        OrderDO order = lifecycleService.createOrder(sn, new BigDecimal("300.00"));
        lifecycleService.pay(order.getId());
        lifecycleService.auditRisk(order.getId(), 10, false); // 进入 WAIT_DELIVER

        // 1. 发货
        OrderShipRequest shipReq = OrderShipRequest.builder()
                .orderId(order.getId())
                .trackingNo("SF888888")
                .build();

        mockMvc.perform(post("/sample/statemachine/order/ship")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(shipReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.fromState").value("WAIT_DELIVER"))
                .andExpect(jsonPath("$.data.toState").value("DELIVERING"));

        // 2. 内部自跃迁追加物流（状态保持 DELIVERING 不变）
        OrderAddTrackingRequest trackReq = OrderAddTrackingRequest.builder()
                .orderId(order.getId())
                .trackingRemark("快件已到达北京分拨中心")
                .build();

        mockMvc.perform(post("/sample/statemachine/order/add-tracking")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(trackReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.fromState").value("DELIVERING"))
                .andExpect(jsonPath("$.data.toState").value("DELIVERING"));

        // 3. 妥投签收
        mockMvc.perform(post("/sample/statemachine/order/" + order.getId() + "/confirm-delivery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toState").value("DELIVERED"));

        // 4. 交易归档完成
        mockMvc.perform(post("/sample/statemachine/order/" + order.getId() + "/finish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toState").value("FINISHED"));
    }

    @Test
    @DisplayName("OrderController: POST /{orderId}/cancel 多源汇聚取消订单")
    void testCancel_MultiFrom() throws Exception {
        String sn = "MOCK-ORD-CANCEL-" + UUID.randomUUID().toString().substring(0, 8);
        OrderDO order = lifecycleService.createOrder(sn, new BigDecimal("120.00"));

        mockMvc.perform(post("/sample/statemachine/order/" + order.getId() + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.fromState").value("WAIT_PAY"))
                .andExpect(jsonPath("$.data.toState").value("CLOSED"));
    }

    @Test
    @DisplayName("OrderController: POST /{orderId}/apply-refund 多源汇聚申请退款")
    void testApplyRefund_MultiFrom() throws Exception {
        String sn = "MOCK-ORD-REFUND-" + UUID.randomUUID().toString().substring(0, 8);
        OrderDO order = lifecycleService.createOrder(sn, new BigDecimal("150.00"));
        lifecycleService.pay(order.getId()); // 转为 PAID

        mockMvc.perform(post("/sample/statemachine/order/" + order.getId() + "/apply-refund"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.fromState").value("PAID"))
                .andExpect(jsonPath("$.data.toState").value("REFUNDING"));
    }

    // =========================================================================
    // 2. StateMachineProbeController 实测 (零副作用只读预检探测与动态按钮权限)
    // =========================================================================

    @Test
    @DisplayName("ProbeController: POST /can-fire 与 POST /verify 纯只读探测可行性与推演")
    void testProbe_CanFireAndVerify() throws Exception {
        String sn = "MOCK-ORD-PROBE-" + UUID.randomUUID().toString().substring(0, 8);
        OrderDO order = lifecycleService.createOrder(sn, new BigDecimal("99.00"));

        // 探测 PAY 事件是否允许流转 -> true
        OrderProbeRequest probeReq = OrderProbeRequest.builder()
                .orderId(order.getId())
                .event(OrderEvent.PAY)
                .build();

        mockMvc.perform(post("/sample/statemachine/probe/can-fire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(probeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 试算推演目标状态
        mockMvc.perform(post("/sample/statemachine/probe/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(probeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.fromState").value("WAIT_PAY"))
                .andExpect(jsonPath("$.data.toState").value("PAID"));
    }

    @Test
    @DisplayName("ProbeController: GET /{orderId}/available-events 与 /{orderId}/button-actions")
    void testProbe_AvailableEventsAndButtonPermissions() throws Exception {
        String sn = "MOCK-ORD-BTN-" + UUID.randomUUID().toString().substring(0, 8);
        OrderDO order = lifecycleService.createOrder(sn, new BigDecimal("123.00"));

        // 反查可用事件列表
        mockMvc.perform(get("/sample/statemachine/probe/" + order.getId() + "/available-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasItem("PAY")))
                .andExpect(jsonPath("$.data", hasItem("CANCEL")));

        // 评估按钮权限
        mockMvc.perform(get("/sample/statemachine/probe/" + order.getId() + "/button-actions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderId").value(order.getId()))
                .andExpect(jsonPath("$.data.buttonPermissions.btnPay").value(true))
                .andExpect(jsonPath("$.data.buttonPermissions.btnCancel").value(true))
                .andExpect(jsonPath("$.data.buttonPermissions.btnShip").value(false));
    }

    // =========================================================================
    // 3. StateMachineCallbackController 实测 (支付异步回调并发与声明式幂等)
    // =========================================================================

    @Test
    @DisplayName("CallbackController: POST /payment 正常回调与声明式幂等重复放行")
    void testCallback_NormalAndIdempotency() throws Exception {
        String sn = "MOCK-ORD-CB-" + UUID.randomUUID().toString().substring(0, 8);
        OrderDO order = lifecycleService.createOrder(sn, new BigDecimal("188.00"));

        OrderPaymentCallbackRequest cbReq = OrderPaymentCallbackRequest.builder()
                .orderId(order.getId())
                .paidAmount(new BigDecimal("188.00"))
                .channelTxNo("WX_PAY_TX_1001")
                .build();

        // 1. 首次支付回调成功
        mockMvc.perform(post("/sample/statemachine/callback/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cbReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.idempotent").value(false))
                .andExpect(jsonPath("$.data.toState").value("PAID"));

        // 2. 第二次重复支付回调触发声明式幂等放行 (idempotent(true))
        mockMvc.perform(post("/sample/statemachine/callback/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cbReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.idempotent").value(true))
                .andExpect(jsonPath("$.data.toState").value("PAID"));
    }

    @Test
    @DisplayName("CallbackController: POST /simulate-concurrent 高并发冲击 CAS 乐观锁防重阻断")
    void testCallback_SimulateConcurrent() throws Exception {
        String sn = "MOCK-ORD-CONCUR-" + UUID.randomUUID().toString().substring(0, 8);
        OrderDO order = lifecycleService.createOrder(sn, new BigDecimal("288.00"));

        mockMvc.perform(post("/sample/statemachine/callback/simulate-concurrent")
                        .param("orderId", String.valueOf(order.getId()))
                        .param("threads", "6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.firstSuccessCount").value(1))
                .andExpect(jsonPath("$.data.finalStatus").value("PAID"))
                .andExpect(jsonPath("$.data.finalVersion").value(1));
    }

    // =========================================================================
    // 4. StateMachineBatchController 实测 (大批量切片关单与容错)
    // =========================================================================

    @Test
    @DisplayName("BatchController: POST /cancel-expired 批量切片关单")
    void testBatch_CancelExpiredOrders() throws Exception {
        String sn1 = "MOCK-BATCH-1-" + UUID.randomUUID().toString().substring(0, 8);
        String sn2 = "MOCK-BATCH-2-" + UUID.randomUUID().toString().substring(0, 8);
        OrderDO o1 = lifecycleService.createOrder(sn1, new BigDecimal("50.00"));
        OrderDO o2 = lifecycleService.createOrder(sn2, new BigDecimal("60.00"));

        OrderBatchCancelRequest req = OrderBatchCancelRequest.builder()
                .orderIds(java.util.List.of(o1.getId(), o2.getId()))
                .cancelReason("定时任务批量清理超时订单")
                .batchSize(10)
                .build();

        mockMvc.perform(post("/sample/statemachine/batch/cancel-expired")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.successCount").value(2))
                .andExpect(jsonPath("$.data.failureCount").value(0))
                .andExpect(jsonPath("$.data.allSuccess").value(true));
    }

    @Test
    @DisplayName("BatchController: POST /simulate-partial-failure 批处理部分异常容错自验")
    void testBatch_SimulatePartialFailure() throws Exception {
        mockMvc.perform(post("/sample/statemachine/batch/simulate-partial-failure"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.successCount").value(1))
                .andExpect(jsonPath("$.data.failureCount").value(1))
                .andExpect(jsonPath("$.data.allSuccess").value(false));
    }

    // =========================================================================
    // 5. StateMachineTopologyController 实测 (拓扑可视化与工厂内省)
    // =========================================================================

    @Test
    @DisplayName("TopologyController: GET /mermaid, /plantuml 与 /machines 拓扑图与机台内省")
    void testTopology_ExportAndIntrospection() throws Exception {
        // 1. Mermaid 导出
        mockMvc.perform(get("/sample/statemachine/topology/mermaid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", containsString("stateDiagram-v2")))
                .andExpect(jsonPath("$.data", containsString("WAIT_PAY")));

        // 2. PlantUML 导出
        mockMvc.perform(get("/sample/statemachine/topology/plantuml"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", containsString("@startuml")))
                .andExpect(jsonPath("$.data", containsString("@enduml")));

        // 3. 全局工厂机台内省
        mockMvc.perform(get("/sample/statemachine/topology/machines"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasItem(OrderStateMachineConfig.MACHINE_ID)));
    }
}
