package io.github.atengk.boot.sample.statemachine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.statemachine.config.OrderStateMachineConfig;
import io.github.atengk.boot.sample.statemachine.constant.OrderEvent;
import io.github.atengk.boot.sample.statemachine.constant.OrderState;
import io.github.atengk.boot.sample.statemachine.context.OrderContext;
import io.github.atengk.boot.sample.statemachine.dto.OrderCreateRequest;
import io.github.atengk.boot.sample.statemachine.dto.OrderTrackingRequest;
import io.github.atengk.boot.sample.statemachine.dto.OrderTransitionRequest;
import io.github.atengk.boot.sample.statemachine.entity.OrderDO;
import io.github.atengk.boot.sample.statemachine.mapper.OrderMapper;
import io.github.atengk.boot.sample.statemachine.service.OrderService;
import io.github.atengk.boot.sample.statemachine.vo.OrderVO;
import io.github.atengk.boot.statemachine.cas.StateMachineCasTemplate;
import io.github.atengk.boot.statemachine.core.StateMachine;
import io.github.atengk.boot.statemachine.core.StateMachineFactory;
import io.github.atengk.boot.statemachine.exception.StateMachineException;
import io.github.atengk.boot.statemachine.model.TransitionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 订单生命周期 8 大状态与核心状态机机制端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderLifecycleIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private StateMachine<OrderState, OrderEvent, OrderContext> orderStateMachine;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("双通道获取状态机：Spring 注入与 StateMachineFactory 查找保持一致")
    void testStateMachineFactoryLookup() {
        StateMachine<OrderState, OrderEvent, OrderContext> found = StateMachineFactory.get(OrderStateMachineConfig.MACHINE_ID);
        assertThat(found).isNotNull();
        assertThat(found.getMachineId()).isEqualTo(OrderStateMachineConfig.MACHINE_ID);
        assertThat(found).isSameAs(orderStateMachine);
    }

    @Test
    @DisplayName("端到端全生命周期主干流转：WAIT_PAY -> PAID -> WAIT_DELIVER -> DELIVERING -> DELIVERED -> FINISHED")
    void testHappyPathFullLifecycle() {
        String sn = "ORD-HAPPY-" + UUID.randomUUID().toString().substring(0, 8);
        OrderVO created = orderService.createOrder(OrderCreateRequest.builder()
                .orderSn(sn)
                .amount(new BigDecimal("199.99"))
                .build());
        Long id = created.getId();
        assertThat(created.getStatus()).isEqualTo(OrderState.WAIT_PAY);

        // 1. 买家付款：WAIT_PAY -> PAID
        TransitionResult<OrderState> payResult = orderService.processTransition(id, OrderTransitionRequest.builder()
                .event(OrderEvent.PAY)
                .build());
        assertThat(payResult.isSuccess()).isTrue();
        assertThat(payResult.getTargetState()).isEqualTo(OrderState.PAID);
        assertThat(orderService.getOrder(id).getStatus()).isEqualTo(OrderState.PAID);

        // 2. Choice 动态路由（低风险放行）：PAID -> WAIT_DELIVER
        TransitionResult<OrderState> auditResult = orderService.processTransition(id, OrderTransitionRequest.builder()
                .event(OrderEvent.RISK_AUDIT)
                .riskScore(20)
                .build());
        assertThat(auditResult.isSuccess()).isTrue();
        assertThat(auditResult.getTargetState()).isEqualTo(OrderState.WAIT_DELIVER);
        assertThat(orderService.getOrder(id).getStatus()).isEqualTo(OrderState.WAIT_DELIVER);

        // 3. 商家发货：WAIT_DELIVER -> DELIVERING
        TransitionResult<OrderState> shipResult = orderService.processTransition(id, OrderTransitionRequest.builder()
                .event(OrderEvent.SHIP)
                .trackingNo("SF123456789")
                .build());
        assertThat(shipResult.isSuccess()).isTrue();
        assertThat(shipResult.getTargetState()).isEqualTo(OrderState.DELIVERING);
        assertThat(orderService.getOrder(id).getTrackingNo()).isEqualTo("SF123456789");

        // 4. 买家签收妥投：DELIVERING -> DELIVERED
        TransitionResult<OrderState> deliveryResult = orderService.processTransition(id, OrderTransitionRequest.builder()
                .event(OrderEvent.CONFIRM_DELIVERY)
                .build());
        assertThat(deliveryResult.isSuccess()).isTrue();
        assertThat(deliveryResult.getTargetState()).isEqualTo(OrderState.DELIVERED);

        // 5. 交易完成：DELIVERED -> FINISHED
        TransitionResult<OrderState> finishResult = orderService.processTransition(id, OrderTransitionRequest.builder()
                .event(OrderEvent.FINISH)
                .build());
        assertThat(finishResult.isSuccess()).isTrue();
        assertThat(finishResult.getTargetState()).isEqualTo(OrderState.FINISHED);
        assertThat(orderService.getOrder(id).getStatus()).isEqualTo(OrderState.FINISHED);
    }

    @Test
    @DisplayName("内部自跃迁：DELIVERING 状态下追加多次物流动态，状态保持不变")
    void testInternalTransitionKeepsState() {
        String sn = "ORD-INTERNAL-" + UUID.randomUUID().toString().substring(0, 8);
        OrderVO created = orderService.createOrder(OrderCreateRequest.builder()
                .orderSn(sn)
                .amount(new BigDecimal("88.00"))
                .build());
        Long id = created.getId();

        // 流转至 DELIVERING
        orderService.processTransition(id, OrderTransitionRequest.builder().event(OrderEvent.PAY).build());
        orderService.processTransition(id, OrderTransitionRequest.builder().event(OrderEvent.RISK_AUDIT).riskScore(10).build());
        orderService.processTransition(id, OrderTransitionRequest.builder().event(OrderEvent.SHIP).trackingNo("JD987654321").build());

        OrderVO before = orderService.getOrder(id);
        assertThat(before.getStatus()).isEqualTo(OrderState.DELIVERING);

        // 首次内部自跃迁
        TransitionResult<OrderState> track1 = orderService.addTrackingInfo(id, OrderTrackingRequest.builder()
                .trackingNo("JD987654321")
                .trackingRemark("快件已到达【北京分拨中心】，准备发往下一站")
                .build());
        assertThat(track1.isSuccess()).isTrue();
        assertThat(track1.getTargetState()).isEqualTo(OrderState.DELIVERING);

        OrderVO after1 = orderService.getOrder(id);
        assertThat(after1.getStatus()).isEqualTo(OrderState.DELIVERING);
        assertThat(after1.getTrackingRemark()).contains("北京分拨中心");

        // 二次内部自跃迁
        TransitionResult<OrderState> track2 = orderService.addTrackingInfo(id, OrderTrackingRequest.builder()
                .trackingNo("JD987654321")
                .trackingRemark("快件正在派送中，派件员电话：13800000000")
                .build());
        assertThat(track2.isSuccess()).isTrue();
        assertThat(track2.getTargetState()).isEqualTo(OrderState.DELIVERING);

        OrderVO after2 = orderService.getOrder(id);
        assertThat(after2.getStatus()).isEqualTo(OrderState.DELIVERING);
        assertThat(after2.getTrackingRemark()).contains("快件正在派送中");
    }

    @Test
    @DisplayName("Choice 动态分支：高风险订单 (riskScore >= 80) 流向 REFUNDING 退款拦截")
    void testChoiceTransitionHighRiskToRefunding() {
        String sn = "ORD-RISK-" + UUID.randomUUID().toString().substring(0, 8);
        OrderVO created = orderService.createOrder(OrderCreateRequest.builder()
                .orderSn(sn)
                .amount(new BigDecimal("9999.00"))
                .build());
        Long id = created.getId();

        orderService.processTransition(id, OrderTransitionRequest.builder().event(OrderEvent.PAY).build());

        // 风控评估打分 95（>= 80 判定高危）
        TransitionResult<OrderState> auditResult = orderService.processTransition(id, OrderTransitionRequest.builder()
                .event(OrderEvent.RISK_AUDIT)
                .riskScore(95)
                .build());
        assertThat(auditResult.isSuccess()).isTrue();
        assertThat(auditResult.getTargetState()).isEqualTo(OrderState.REFUNDING);
        assertThat(orderService.getOrder(id).getStatus()).isEqualTo(OrderState.REFUNDING);

        // 退款完成后流向 CLOSED 终态
        TransitionResult<OrderState> finishResult = orderService.processTransition(id, OrderTransitionRequest.builder()
                .event(OrderEvent.FINISH)
                .build());
        assertThat(finishResult.isSuccess()).isTrue();
        assertThat(finishResult.getTargetState()).isEqualTo(OrderState.CLOSED);
        assertThat(orderService.getOrder(id).getStatus()).isEqualTo(OrderState.CLOSED);
    }

    @Test
    @DisplayName("多源汇聚跃迁：WAIT_PAY / PAID / WAIT_DELIVER 均可由 CANCEL 事件统一收敛至 CLOSED")
    void testMultiFromTransitionCancel() {
        // 场景 1：待支付取消
        String sn1 = "ORD-CANCEL-1-" + UUID.randomUUID().toString().substring(0, 6);
        OrderVO o1 = orderService.createOrder(OrderCreateRequest.builder().orderSn(sn1).amount(BigDecimal.TEN).build());
        TransitionResult<OrderState> r1 = orderService.processTransition(o1.getId(), OrderTransitionRequest.builder().event(OrderEvent.CANCEL).build());
        assertThat(r1.isSuccess()).isTrue();
        assertThat(r1.getTargetState()).isEqualTo(OrderState.CLOSED);

        // 场景 2：已支付取消
        String sn2 = "ORD-CANCEL-2-" + UUID.randomUUID().toString().substring(0, 6);
        OrderVO o2 = orderService.createOrder(OrderCreateRequest.builder().orderSn(sn2).amount(BigDecimal.TEN).build());
        orderService.processTransition(o2.getId(), OrderTransitionRequest.builder().event(OrderEvent.PAY).build());
        TransitionResult<OrderState> r2 = orderService.processTransition(o2.getId(), OrderTransitionRequest.builder().event(OrderEvent.CANCEL).build());
        assertThat(r2.isSuccess()).isTrue();
        assertThat(r2.getTargetState()).isEqualTo(OrderState.CLOSED);

        // 场景 3：待发货取消
        String sn3 = "ORD-CANCEL-3-" + UUID.randomUUID().toString().substring(0, 6);
        OrderVO o3 = orderService.createOrder(OrderCreateRequest.builder().orderSn(sn3).amount(BigDecimal.TEN).build());
        orderService.processTransition(o3.getId(), OrderTransitionRequest.builder().event(OrderEvent.PAY).build());
        orderService.processTransition(o3.getId(), OrderTransitionRequest.builder().event(OrderEvent.RISK_AUDIT).riskScore(10).build());
        TransitionResult<OrderState> r3 = orderService.processTransition(o3.getId(), OrderTransitionRequest.builder().event(OrderEvent.CANCEL).build());
        assertThat(r3.isSuccess()).isTrue();
        assertThat(r3.getTargetState()).isEqualTo(OrderState.CLOSED);
    }

    @Test
    @DisplayName("CAS 乐观锁防重校验：并发重复触发同一跃迁时拒绝并抛出 CONCURRENT_CAS_CONFLICT")
    void testCasConcurrencyConflict() throws InterruptedException {
        String sn = "ORD-CAS-" + UUID.randomUUID().toString().substring(0, 8);
        OrderVO created = orderService.createOrder(OrderCreateRequest.builder()
                .orderSn(sn)
                .amount(new BigDecimal("50.00"))
                .build());
        Long id = created.getId();

        // 模拟并发：两个并发请求尝试同时支付
        int threadCount = 2;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger casConflictCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    orderService.processTransition(id, OrderTransitionRequest.builder().event(OrderEvent.PAY).build());
                    successCount.incrementAndGet();
                } catch (StateMachineException e) {
                    if (e.getResultCode().getCode() == 59005) {
                        casConflictCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        endLatch.await();

        // 必有 1 次成功，1 次因版本号或状态已更新触发 CAS 冲突拒绝
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(casConflictCount.get()).isEqualTo(1);
        assertThat(orderService.getOrder(id).getStatus()).isEqualTo(OrderState.PAID);
    }

    @Test
    @DisplayName("事务回滚验证：Action 抛出异常触发 Spring 本地事务回滚，数据库状态保持原样")
    void testActionExceptionTriggersTransactionRollback() {
        String sn = "ORD-ROLLBACK-" + UUID.randomUUID().toString().substring(0, 8);
        OrderVO created = orderService.createOrder(OrderCreateRequest.builder()
                .orderSn(sn)
                .amount(new BigDecimal("120.00"))
                .build());
        Long id = created.getId();

        // 支付成功变为 PAID
        orderService.processTransition(id, OrderTransitionRequest.builder().event(OrderEvent.PAY).build());
        assertThat(orderService.getOrder(id).getStatus()).isEqualTo(OrderState.PAID);

        // 触发风控，并设置 failTrigger = true 模拟 Action 抛出异常
        assertThatThrownBy(() -> orderService.processTransition(id, OrderTransitionRequest.builder()
                .event(OrderEvent.RISK_AUDIT)
                .riskScore(10)
                .failTrigger(true)
                .build()))
                .isInstanceOf(StateMachineException.class)
                .hasMessageContaining("模拟 Action 执行业务异常");

        // 验证数据库状态事务原子回滚，依然保持为 PAID，未被错误持久化为 WAIT_DELIVER
        OrderDO currentInDb = orderMapper.selectById(id);
        assertThat(currentInDb.getStatus()).isEqualTo(OrderState.PAID);
    }

    @Test
    @DisplayName("Web 端点端到端集成测试：创建订单、触发跃迁与导出 Mermaid 图例")
    void testWebControllerAndMermaidEndpoint() throws Exception {
        // 1. 创建订单
        String sn = "ORD-WEB-" + UUID.randomUUID().toString().substring(0, 6);
        OrderCreateRequest createReq = OrderCreateRequest.builder()
                .orderSn(sn)
                .amount(new BigDecimal("365.00"))
                .build();

        String responseJson = mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderSn").value(sn))
                .andExpect(jsonPath("$.data.status").value("WAIT_PAY"))
                .andReturn().getResponse().getContentAsString();

        Long orderId = objectMapper.readTree(responseJson).path("data").path("id").asLong();

        // 2. 导出 Mermaid 语法图例端点
        mockMvc.perform(get("/orders/statemachine/mermaid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.containsString("stateDiagram-v2")))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.containsString("WAIT_PAY --> PAID : PAY")))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.containsString("DELIVERING --> DELIVERING : ADD_TRACKING")));

        // 3. Web 触发跃迁
        OrderTransitionRequest payReq = OrderTransitionRequest.builder().event(OrderEvent.PAY).build();
        mockMvc.perform(post("/orders/" + orderId + "/transitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.targetState").value("PAID"));
    }
}
