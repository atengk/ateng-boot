package io.github.atengk.boot.statemachine.core;

import io.github.atengk.boot.statemachine.builder.StateMachineBuilder;
import io.github.atengk.boot.statemachine.builder.StateMachineBuilderFactory;
import io.github.atengk.boot.statemachine.enums.StateMachineResultCode;
import io.github.atengk.boot.statemachine.exception.StateMachineException;
import io.github.atengk.boot.statemachine.model.BatchOptions;
import io.github.atengk.boot.statemachine.model.BatchTransitionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 大批量切片流转引擎与结构化批量聚合单元测试
 *
 * @author Ateng
 * @since 2026-11-01
 */
class BatchTransitionTest {

    enum OrderState {
        WAIT_PAY, PAID, CANCELLED
    }

    enum OrderEvent {
        PAY, CANCEL
    }

    static class Order {
        private final long id;
        private OrderState state;

        public Order(long id, OrderState state) {
            this.id = id;
            this.state = state;
        }

        public long getId() {
            return id;
        }

        public OrderState getState() {
            return state;
        }

        public void setState(OrderState state) {
            this.state = state;
        }
    }

    private StateMachine<OrderState, OrderEvent, Void> createOrderStateMachine(AtomicInteger actionCounter) {
        StateMachineBuilder<OrderState, OrderEvent, Void> builder = StateMachineBuilderFactory.create();
        builder.externalTransition()
                .from(OrderState.WAIT_PAY)
                .to(OrderState.PAID)
                .on(OrderEvent.PAY)
                .perform((from, to, event, ctx) -> {
                    if (actionCounter != null) {
                        actionCounter.incrementAndGet();
                    }
                });

        builder.externalTransition()
                .from(OrderState.WAIT_PAY)
                .to(OrderState.CANCELLED)
                .on(OrderEvent.CANCEL);

        return builder.build("ORDER_BATCH_MACHINE");
    }

    @Test
    @DisplayName("测试 1,000+ 实体大批量切片流转全量成功 (BatchSize=200, 5 slices)")
    void testBatchTransition1000EntitiesSuccess() {
        AtomicInteger counter = new AtomicInteger(0);
        StateMachine<OrderState, OrderEvent, Void> machine = createOrderStateMachine(counter);

        List<Order> orders = new ArrayList<>(1000);
        for (int i = 1; i <= 1000; i++) {
            orders.add(new Order(i, OrderState.WAIT_PAY));
        }

        BatchOptions options = BatchOptions.of(200);
        BatchTransitionResult<OrderState, Order> result = machine.fireEventBatch(
                orders,
                Order::getState,
                Order::setState,
                OrderEvent.PAY,
                null,
                options
        );

        assertThat(result.getTotalCount()).isEqualTo(1000);
        assertThat(result.getSuccessCount()).isEqualTo(1000);
        assertThat(result.getFailureCount()).isEqualTo(0);
        assertThat(result.isAllSuccess()).isTrue();
        assertThat(result.hasFailures()).isFalse();
        assertThat(result.getSuccessEntities()).hasSize(1000);
        assertThat(result.getFailures()).isEmpty();
        assertThat(counter.get()).isEqualTo(1000);

        // 验证所有实体内存状态回写正确
        for (Order order : orders) {
            assertThat(order.getState()).isEqualTo(OrderState.PAID);
        }
    }

    @Test
    @DisplayName("测试 Fail-Safe 模式下部分失败错误隔离: 成功推进，失败收集并不影响后续有效实体")
    void testBatchTransitionFailSafePartialFailures() {
        StateMachine<OrderState, OrderEvent, Void> machine = createOrderStateMachine(null);

        List<Order> orders = new ArrayList<>(1000);
        // 前 500 个有效 WAIT_PAY，后 500 个非法 CANCELLED (不能执行 PAY)
        for (int i = 1; i <= 500; i++) {
            orders.add(new Order(i, OrderState.WAIT_PAY));
        }
        for (int i = 501; i <= 1000; i++) {
            orders.add(new Order(i, OrderState.CANCELLED));
        }

        BatchOptions options = BatchOptions.builder()
                .batchSize(200)
                .continueOnError(true)
                .build();

        BatchTransitionResult<OrderState, Order> result = machine.fireEventBatch(
                orders,
                Order::getState,
                Order::setState,
                OrderEvent.PAY,
                options
        );

        assertThat(result.getTotalCount()).isEqualTo(1000);
        assertThat(result.getSuccessCount()).isEqualTo(500);
        assertThat(result.getFailureCount()).isEqualTo(500);
        assertThat(result.isAllSuccess()).isFalse();
        assertThat(result.hasFailures()).isTrue();

        // 验证成功实体
        assertThat(result.getSuccessEntities()).hasSize(500);
        for (int i = 0; i < 500; i++) {
            assertThat(orders.get(i).getState()).isEqualTo(OrderState.PAID);
        }

        // 验证失败实体明细
        assertThat(result.getFailures()).hasSize(500);
        for (int i = 500; i < 1000; i++) {
            Order failedOrder = orders.get(i);
            assertThat(failedOrder.getState()).isEqualTo(OrderState.CANCELLED);
            StateMachineException ex = result.getFailures().get(failedOrder);
            assertThat(ex).isNotNull();
            assertThat(ex.getCode()).isEqualTo(StateMachineResultCode.TRANSITION_NOT_FOUND.getCode());
        }
    }

    @Test
    @DisplayName("测试 Fail-Fast 模式下遭遇首个异常立即熔断中断 (BATCH_TRANSITION_FAILED 59008)")
    void testBatchTransitionFailFastAborts() {
        StateMachine<OrderState, OrderEvent, Void> machine = createOrderStateMachine(null);

        List<Order> orders = new ArrayList<>(1000);
        for (int i = 1; i <= 1000; i++) {
            // 第 250 个实体非法状态 CANCELLED
            if (i == 250) {
                orders.add(new Order(i, OrderState.CANCELLED));
            } else {
                orders.add(new Order(i, OrderState.WAIT_PAY));
            }
        }

        BatchOptions options = BatchOptions.failFast(100);

        assertThatThrownBy(() -> machine.fireEventBatch(
                orders,
                Order::getState,
                Order::setState,
                OrderEvent.PAY,
                options
        ))
                .isInstanceOf(StateMachineException.class)
                .satisfies(ex -> {
                    StateMachineException smEx = (StateMachineException) ex;
                    assertThat(smEx.getCode()).isEqualTo(StateMachineResultCode.BATCH_TRANSITION_FAILED.getCode());
                    assertThat(smEx.getSourceState()).isEqualTo(OrderState.CANCELLED);
                    assertThat(smEx.getEvent()).isEqualTo(OrderEvent.PAY);
                    assertThat(smEx.getCause()).isInstanceOf(StateMachineException.class);
                });

        // 验证第 1~249 个已流转，第 250 个失败，第 251~1000 个被熔断未执行
        for (int i = 0; i < 249; i++) {
            assertThat(orders.get(i).getState()).isEqualTo(OrderState.PAID);
        }
        assertThat(orders.get(249).getState()).isEqualTo(OrderState.CANCELLED);
        for (int i = 250; i < 1000; i++) {
            assertThat(orders.get(i).getState()).isEqualTo(OrderState.WAIT_PAY);
        }
    }

    @Test
    @DisplayName("测试空入参安全防御与重载方法便捷性")
    void testEmptyAndConvenienceOverloads() {
        StateMachine<OrderState, OrderEvent, Void> machine = createOrderStateMachine(null);

        // 空列表返回 empty
        BatchTransitionResult<OrderState, Order> emptyResult = machine.fireEventBatch(
                Collections.emptyList(),
                Order::getState,
                Order::setState,
                OrderEvent.PAY
        );
        assertThat(emptyResult.getTotalCount()).isEqualTo(0);
        assertThat(emptyResult.getSuccessCount()).isEqualTo(0);
        assertThat(emptyResult.getFailureCount()).isEqualTo(0);
        assertThat(emptyResult.isAllSuccess()).isFalse();

        // null list 返回 empty
        assertThat(machine.fireEventBatch(null, Order::getState, Order::setState, OrderEvent.PAY).getTotalCount()).isEqualTo(0);

        // 参数校验
        List<Order> orders = List.of(new Order(1, OrderState.WAIT_PAY));
        assertThatThrownBy(() -> machine.fireEventBatch(orders, null, Order::setState, OrderEvent.PAY))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> machine.fireEventBatch(orders, Order::getState, null, OrderEvent.PAY))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> machine.fireEventBatch(orders, Order::getState, Order::setState, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("测试 BatchOptions 配置属性与校验防御")
    void testBatchOptions() {
        BatchOptions def = BatchOptions.defaults();
        assertThat(def.getBatchSize()).isEqualTo(200);
        assertThat(def.isFailFast()).isFalse();
        assertThat(def.isContinueOnError()).isTrue();

        BatchOptions ff = BatchOptions.failFast(50);
        assertThat(ff.getBatchSize()).isEqualTo(50);
        assertThat(ff.isFailFast()).isTrue();
        assertThat(ff.isContinueOnError()).isFalse();

        BatchOptions custom = BatchOptions.builder()
                .batchSize(100)
                .failFast(false)
                .build();
        assertThat(custom.getBatchSize()).isEqualTo(100);
        assertThat(custom.isFailFast()).isFalse();
        assertThat(custom.toString()).contains("batchSize=100", "failFast=false");

        assertThatThrownBy(() -> BatchOptions.of(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be greater than 0");

        assertThatThrownBy(() -> BatchOptions.builder().batchSize(-5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("测试 BatchTransitionResult 结构化指标辅助方法")
    void testBatchTransitionResultAuxiliaryMethods() {
        Order o1 = new Order(1, OrderState.PAID);
        Order o2 = new Order(2, OrderState.CANCELLED);
        StateMachineException ex = new StateMachineException("test failure");

        BatchTransitionResult<OrderState, Order> result = BatchTransitionResult.of(
                2,
                List.of(o1),
                Collections.singletonMap(o2, ex)
        );

        assertThat(result.getTotalCount()).isEqualTo(2);
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailureCount()).isEqualTo(1);
        assertThat(result.isAllSuccess()).isFalse();
        assertThat(result.hasFailures()).isTrue();
        assertThat(result.getFailureEntities()).containsExactly(o2);
        assertThat(result.toString()).contains("totalCount=2", "successCount=1", "failureCount=1");
    }
}
