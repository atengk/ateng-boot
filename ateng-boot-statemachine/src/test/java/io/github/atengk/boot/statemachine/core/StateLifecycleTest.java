package io.github.atengk.boot.statemachine.core;

import io.github.atengk.boot.statemachine.builder.StateMachineBuilder;
import io.github.atengk.boot.statemachine.builder.StateMachineBuilderFactory;
import io.github.atengk.boot.statemachine.cas.CasUpdater;
import io.github.atengk.boot.statemachine.enums.StateMachineResultCode;
import io.github.atengk.boot.statemachine.exception.StateMachineException;
import io.github.atengk.boot.statemachine.model.StateLifecycle;
import io.github.atengk.boot.statemachine.model.TransitionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 状态节点进入与退出生命周期 (Entry / Exit) 与 UML 语义不变量单元测试
 *
 * @author Ateng
 * @since 2026-11-01
 */
class StateLifecycleTest {

    enum TestState {
        INIT, PROCESSING, COMPLETED, FAILED
    }

    enum TestEvent {
        START, PROCESS, SUCCESS, FAIL, RETRY
    }

    static class TestContext {
        private final List<String> logs = new ArrayList<>();
        private int score;

        public TestContext() {
            this(0);
        }

        public TestContext(int score) {
            this.score = score;
        }

        public List<String> getLogs() {
            return logs;
        }

        public int getScore() {
            return score;
        }

        public void setScore(int score) {
            this.score = score;
        }
    }

    static class OrderEntity {
        private TestState status;

        public OrderEntity(TestState status) {
            this.status = status;
        }

        public TestState getStatus() {
            return status;
        }

        public void setStatus(TestState status) {
            this.status = status;
        }
    }

    @Test
    @DisplayName("测试外部跃迁严格遵循 UML 顺序: oldState.onExit -> transition.perform -> newState.onEntry")
    void testExternalTransitionLifecycleOrder() {
        StateMachineBuilder<TestState, TestEvent, TestContext> builder = StateMachineBuilderFactory.create();

        builder.state(TestState.INIT)
                .onExit((from, to, event, ctx) -> ctx.getLogs().add("EXIT: " + from + " -> " + to + " on " + event));

        builder.state(TestState.PROCESSING)
                .onEntry((from, to, event, ctx) -> ctx.getLogs().add("ENTRY: " + to + " from " + from + " on " + event))
                .onExit(ctx -> ctx.getLogs().add("EXIT_CONSUMER: PROCESSING"));

        builder.state(TestState.COMPLETED)
                .onEntry(ctx -> ctx.getLogs().add("ENTRY_CONSUMER: COMPLETED"));

        builder.externalTransition()
                .from(TestState.INIT)
                .to(TestState.PROCESSING)
                .on(TestEvent.START)
                .perform((from, to, event, ctx) -> ctx.getLogs().add("ACTION: " + from + " -> " + to));

        builder.externalTransition()
                .from(TestState.PROCESSING)
                .to(TestState.COMPLETED)
                .on(TestEvent.SUCCESS)
                .perform((from, to, event, ctx) -> ctx.getLogs().add("ACTION: SUCCESS_PERFORM"));

        StateMachine<TestState, TestEvent, TestContext> machine = builder.build("LIFECYCLE_ORDER_MACHINE");

        TestContext ctx = new TestContext();
        TestState target1 = machine.fireEvent(TestState.INIT, TestEvent.START, ctx);
        assertThat(target1).isEqualTo(TestState.PROCESSING);

        // 验证第一段流转: INIT.exit -> action -> PROCESSING.entry
        assertThat(ctx.getLogs()).containsExactly(
                "EXIT: INIT -> PROCESSING on START",
                "ACTION: INIT -> PROCESSING",
                "ENTRY: PROCESSING from INIT on START"
        );

        ctx.getLogs().clear();
        TestState target2 = machine.fireEvent(TestState.PROCESSING, TestEvent.SUCCESS, ctx);
        assertThat(target2).isEqualTo(TestState.COMPLETED);

        // 验证第二段流转: PROCESSING.exit -> action -> COMPLETED.entry
        assertThat(ctx.getLogs()).containsExactly(
                "EXIT_CONSUMER: PROCESSING",
                "ACTION: SUCCESS_PERFORM",
                "ENTRY_CONSUMER: COMPLETED"
        );
    }

    @Test
    @DisplayName("测试内部跃迁 (Internal Transition) 严格豁免: 绝不触发 onExit 与 onEntry")
    void testInternalTransitionBypassesLifecycle() {
        StateMachineBuilder<TestState, TestEvent, TestContext> builder = StateMachineBuilderFactory.create();

        builder.state(TestState.PROCESSING)
                .onEntry(ctx -> ctx.getLogs().add("ENTRY: PROCESSING"))
                .onExit(ctx -> ctx.getLogs().add("EXIT: PROCESSING"));

        builder.internalTransition()
                .from(TestState.PROCESSING)
                .on(TestEvent.PROCESS)
                .perform((from, to, event, ctx) -> ctx.getLogs().add("INTERNAL_ACTION: " + event));

        StateMachine<TestState, TestEvent, TestContext> machine = builder.build("INTERNAL_LIFECYCLE_MACHINE");

        TestContext ctx = new TestContext();
        TestState target = machine.fireEvent(TestState.PROCESSING, TestEvent.PROCESS, ctx);

        assertThat(target).isEqualTo(TestState.PROCESSING);
        // 严格断言: 仅执行跃迁 Action，绝对不触发 onExit 与 onEntry
        assertThat(ctx.getLogs()).containsExactly("INTERNAL_ACTION: PROCESS");
    }

    @Test
    @DisplayName("测试多源汇聚跃迁的生命周期顺序")
    void testMultiFromTransitionLifecycle() {
        StateMachineBuilder<TestState, TestEvent, TestContext> builder = StateMachineBuilderFactory.create();

        builder.state(TestState.INIT)
                .onExit(ctx -> ctx.getLogs().add("EXIT: INIT"));

        builder.state(TestState.PROCESSING)
                .onExit(ctx -> ctx.getLogs().add("EXIT: PROCESSING"));

        builder.state(TestState.FAILED)
                .onEntry(ctx -> ctx.getLogs().add("ENTRY: FAILED"));

        builder.multiFromTransition()
                .from(TestState.INIT, TestState.PROCESSING)
                .to(TestState.FAILED)
                .on(TestEvent.FAIL)
                .perform(ctx -> ctx.getLogs().add("ACTION: FAIL_NOTIFY"));

        StateMachine<TestState, TestEvent, TestContext> machine = builder.build("MULTI_FROM_LIFECYCLE_MACHINE");

        TestContext ctx1 = new TestContext();
        machine.fireEvent(TestState.INIT, TestEvent.FAIL, ctx1);
        assertThat(ctx1.getLogs()).containsExactly(
                "EXIT: INIT",
                "ACTION: FAIL_NOTIFY",
                "ENTRY: FAILED"
        );

        TestContext ctx2 = new TestContext();
        machine.fireEvent(TestState.PROCESSING, TestEvent.FAIL, ctx2);
        assertThat(ctx2.getLogs()).containsExactly(
                "EXIT: PROCESSING",
                "ACTION: FAIL_NOTIFY",
                "ENTRY: FAILED"
        );
    }

    @Test
    @DisplayName("测试 Choice 条件分支路由生命周期: 跨状态流转触发 Exit/Entry，同状态分支豁免 Exit/Entry")
    void testChoiceTransitionLifecycle() {
        StateMachineBuilder<TestState, TestEvent, TestContext> builder = StateMachineBuilderFactory.create();

        builder.state(TestState.PROCESSING)
                .onExit(ctx -> ctx.getLogs().add("EXIT: PROCESSING"))
                .onEntry(ctx -> ctx.getLogs().add("ENTRY: PROCESSING"));

        builder.state(TestState.COMPLETED)
                .onEntry(ctx -> ctx.getLogs().add("ENTRY: COMPLETED"));

        builder.choiceTransition()
                .from(TestState.PROCESSING)
                .on(TestEvent.SUCCESS)
                .when(ctx -> ctx.getScore() >= 60).to(TestState.COMPLETED)
                .defaultTo(TestState.PROCESSING)
                .perform(ctx -> ctx.getLogs().add("ACTION: CHOICE_PERFORM"));

        StateMachine<TestState, TestEvent, TestContext> machine = builder.build("CHOICE_LIFECYCLE_MACHINE");

        // 场景 A: 分支命中不同状态 (PROCESSING -> COMPLETED)
        TestContext ctxA = new TestContext(80);
        TestState targetA = machine.fireEvent(TestState.PROCESSING, TestEvent.SUCCESS, ctxA);
        assertThat(targetA).isEqualTo(TestState.COMPLETED);
        assertThat(ctxA.getLogs()).containsExactly(
                "EXIT: PROCESSING",
                "ACTION: CHOICE_PERFORM",
                "ENTRY: COMPLETED"
        );

        // 场景 B: 默认分支回到相同状态 (PROCESSING -> PROCESSING)
        TestContext ctxB = new TestContext(40);
        TestState targetB = machine.fireEvent(TestState.PROCESSING, TestEvent.SUCCESS, ctxB);
        assertThat(targetB).isEqualTo(TestState.PROCESSING);
        // 同状态流转豁免 Exit 与 Entry
        assertThat(ctxB.getLogs()).containsExactly(
                "ACTION: CHOICE_PERFORM"
        );
    }

    @Test
    @DisplayName("测试外部自跃迁 (External Self-Transition): 严格执行 exit -> perform -> entry")
    void testExternalSelfTransitionLifecycle() {
        StateMachineBuilder<TestState, TestEvent, TestContext> builder = StateMachineBuilderFactory.create();

        builder.state(TestState.PROCESSING)
                .onExit(ctx -> ctx.getLogs().add("EXIT: PROCESSING"))
                .onEntry(ctx -> ctx.getLogs().add("ENTRY: PROCESSING"));

        builder.externalTransition()
                .from(TestState.PROCESSING)
                .to(TestState.PROCESSING)
                .on(TestEvent.RETRY)
                .perform(ctx -> ctx.getLogs().add("ACTION: RETRY_PERFORM"));

        StateMachine<TestState, TestEvent, TestContext> machine = builder.build("EXTERNAL_SELF_MACHINE");

        TestContext ctx = new TestContext();
        TestState target = machine.fireEvent(TestState.PROCESSING, TestEvent.RETRY, ctx);
        assertThat(target).isEqualTo(TestState.PROCESSING);
        assertThat(ctx.getLogs()).containsExactly(
                "EXIT: PROCESSING",
                "ACTION: RETRY_PERFORM",
                "ENTRY: PROCESSING"
        );
    }

    @Test
    @DisplayName("测试多个生命周期动作与 and() 连续链式装配")
    void testMultipleLifecycleActionsAndChaining() {
        StateMachineBuilder<TestState, TestEvent, TestContext> builder = StateMachineBuilderFactory.create();

        // 使用 and() 链式配置两个状态的生命周期
        builder.state(TestState.INIT)
                .onExit(ctx -> ctx.getLogs().add("EXIT_1: INIT"))
                .onExit(ctx -> ctx.getLogs().add("EXIT_2: INIT"))
                .and()
                .state(TestState.PROCESSING)
                .onEntry(ctx -> ctx.getLogs().add("ENTRY_1: PROCESSING"))
                .onEntry(ctx -> ctx.getLogs().add("ENTRY_2: PROCESSING"));

        builder.externalTransition()
                .from(TestState.INIT)
                .to(TestState.PROCESSING)
                .on(TestEvent.START)
                .perform(ctx -> ctx.getLogs().add("ACTION: START"));

        StateMachine<TestState, TestEvent, TestContext> machine = builder.build("MULTI_ACTION_MACHINE");

        TestContext ctx = new TestContext();
        machine.fireEvent(TestState.INIT, TestEvent.START, ctx);

        assertThat(ctx.getLogs()).containsExactly(
                "EXIT_1: INIT",
                "EXIT_2: INIT",
                "ACTION: START",
                "ENTRY_1: PROCESSING",
                "ENTRY_2: PROCESSING"
        );
    }

    @Test
    @DisplayName("测试源状态 Exit 动作异常阻断后续流转与状态迁移 (STATE_LIFECYCLE_FAILED 59007)")
    void testExitActionExceptionAbortsTransition() {
        StateMachineBuilder<TestState, TestEvent, TestContext> builder = StateMachineBuilderFactory.create();

        builder.state(TestState.INIT)
                .onExit((from, to, event, ctx) -> {
                    ctx.getLogs().add("EXIT: INIT");
                    throw new IllegalStateException("磁盘空间不足无法退出初始化");
                });

        builder.state(TestState.PROCESSING)
                .onEntry(ctx -> ctx.getLogs().add("ENTRY: PROCESSING"));

        builder.externalTransition()
                .from(TestState.INIT)
                .to(TestState.PROCESSING)
                .on(TestEvent.START)
                .perform(ctx -> ctx.getLogs().add("ACTION: START"));

        StateMachine<TestState, TestEvent, TestContext> machine = builder.build("EXIT_FAIL_MACHINE");

        TestContext ctx = new TestContext();
        assertThatThrownBy(() -> machine.fireEvent(TestState.INIT, TestEvent.START, ctx))
                .isInstanceOf(StateMachineException.class)
                .satisfies(ex -> {
                    StateMachineException smEx = (StateMachineException) ex;
                    assertThat(smEx.getCode()).isEqualTo(StateMachineResultCode.STATE_LIFECYCLE_FAILED.getCode());
                    assertThat(smEx.getSourceState()).isEqualTo(TestState.INIT);
                    assertThat(smEx.getTargetState()).isEqualTo(TestState.PROCESSING);
                    assertThat(smEx.getEvent()).isEqualTo(TestEvent.START);
                    assertThat(smEx.getCause()).isInstanceOf(IllegalStateException.class);
                    assertThat(smEx.getCause().getMessage()).isEqualTo("磁盘空间不足无法退出初始化");
                });

        // 仅 Exit 执行到了异常处，后续的 Action 和 Entry 绝不执行
        assertThat(ctx.getLogs()).containsExactly("EXIT: INIT");
    }

    @Test
    @DisplayName("测试目标状态 Entry 动作异常阻断流转 (STATE_LIFECYCLE_FAILED 59007)")
    void testEntryActionExceptionAbortsTransition() {
        StateMachineBuilder<TestState, TestEvent, TestContext> builder = StateMachineBuilderFactory.create();

        builder.state(TestState.INIT)
                .onExit(ctx -> ctx.getLogs().add("EXIT: INIT"));

        builder.state(TestState.PROCESSING)
                .onEntry((from, to, event, ctx) -> {
                    ctx.getLogs().add("ENTRY: PROCESSING");
                    throw new RuntimeException("初始化处理环境失败");
                });

        builder.externalTransition()
                .from(TestState.INIT)
                .to(TestState.PROCESSING)
                .on(TestEvent.START)
                .perform(ctx -> ctx.getLogs().add("ACTION: START"))
                .performAfterCommit(ctx -> ctx.getLogs().add("POST_COMMIT: NOTIFY"));

        StateMachine<TestState, TestEvent, TestContext> machine = builder.build("ENTRY_FAIL_MACHINE");

        TestContext ctx = new TestContext();
        assertThatThrownBy(() -> machine.fireEvent(TestState.INIT, TestEvent.START, ctx))
                .isInstanceOf(StateMachineException.class)
                .satisfies(ex -> {
                    StateMachineException smEx = (StateMachineException) ex;
                    assertThat(smEx.getCode()).isEqualTo(StateMachineResultCode.STATE_LIFECYCLE_FAILED.getCode());
                    assertThat(smEx.getSourceState()).isEqualTo(TestState.INIT);
                    assertThat(smEx.getTargetState()).isEqualTo(TestState.PROCESSING);
                    assertThat(smEx.getEvent()).isEqualTo(TestEvent.START);
                    assertThat(smEx.getCause()).isInstanceOf(RuntimeException.class);
                    assertThat(smEx.getCause().getMessage()).isEqualTo("初始化处理环境失败");
                });

        // Exit 和 Action 执行完毕，Entry 抛异常，PostCommit 绝不执行
        assertThat(ctx.getLogs()).containsExactly(
                "EXIT: INIT",
                "ACTION: START",
                "ENTRY: PROCESSING"
        );
    }

    @Test
    @DisplayName("测试静默模式 fireEventQuietly 对 Entry/Exit 异常的安全捕获")
    void testFireEventQuietlyLifecycleException() {
        StateMachineBuilder<TestState, TestEvent, TestContext> builder = StateMachineBuilderFactory.create();

        builder.state(TestState.INIT)
                .onExit((from, to, event, ctx) -> {
                    throw new IllegalStateException("退出异常");
                });

        builder.externalTransition()
                .from(TestState.INIT)
                .to(TestState.PROCESSING)
                .on(TestEvent.START);

        StateMachine<TestState, TestEvent, TestContext> machine = builder.build("QUIET_FAIL_MACHINE");

        TestContext ctx = new TestContext();
        TransitionResult<TestState> result = machine.fireEventQuietly(TestState.INIT, TestEvent.START, ctx);

        assertThat(result.isAccepted()).isFalse();
        assertThat(result.getFailureReason()).contains("生命周期动作执行失败");
        assertThat(result.getCause()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("测试 CAS 乐观锁流转门面与生命周期动作联动")
    void testCasLifecycleOrder() {
        StateMachineBuilder<TestState, TestEvent, TestContext> builder = StateMachineBuilderFactory.create();

        builder.state(TestState.INIT)
                .onExit(ctx -> ctx.getLogs().add("EXIT: INIT"));

        builder.state(TestState.PROCESSING)
                .onEntry(ctx -> ctx.getLogs().add("ENTRY: PROCESSING"));

        builder.externalTransition()
                .from(TestState.INIT)
                .to(TestState.PROCESSING)
                .on(TestEvent.START)
                .perform(ctx -> ctx.getLogs().add("ACTION: START"));

        StateMachine<TestState, TestEvent, TestContext> machine = builder.build("CAS_LIFECYCLE_MACHINE");

        OrderEntity order = new OrderEntity(TestState.INIT);
        TestContext ctx = new TestContext();
        AtomicInteger updateCount = new AtomicInteger(0);

        CasUpdater<OrderEntity, TestState> casUpdater = (entity, oldState, newState) -> {
            ctx.getLogs().add("CAS_DB_UPDATE: " + oldState + " -> " + newState);
            updateCount.incrementAndGet();
            return 1;
        };

        TestState target = machine.fireEventWithCas(
                order,
                OrderEntity::getStatus,
                OrderEntity::setStatus,
                TestEvent.START,
                ctx,
                casUpdater
        );

        assertThat(target).isEqualTo(TestState.PROCESSING);
        assertThat(order.getStatus()).isEqualTo(TestState.PROCESSING);

        // 验证 CAS 模式下的完整生命周期时序: CAS DB -> EXIT -> ACTION -> ENTRY
        assertThat(ctx.getLogs()).containsExactly(
                "CAS_DB_UPDATE: INIT -> PROCESSING",
                "EXIT: INIT",
                "ACTION: START",
                "ENTRY: PROCESSING"
        );
    }

    @Test
    @DisplayName("测试防御性前置校验与空指针卫语句拦截")
    void testDefensiveValidation() {
        StateMachineBuilder<TestState, TestEvent, TestContext> builder = StateMachineBuilderFactory.create();

        assertThatThrownBy(() -> builder.state(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("state must not be null");

        var lifecycleBuilder = builder.state(TestState.INIT);

        assertThatThrownBy(() -> lifecycleBuilder.onEntry((io.github.atengk.boot.statemachine.core.Action<TestState, TestEvent, TestContext>) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");

        assertThatThrownBy(() -> lifecycleBuilder.onExit((io.github.atengk.boot.statemachine.core.Action<TestState, TestEvent, TestContext>) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");

        assertThatThrownBy(() -> lifecycleBuilder.onEntry((java.util.function.Consumer<TestContext>) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");

        assertThatThrownBy(() -> lifecycleBuilder.onExit((java.util.function.Consumer<TestContext>) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    @DisplayName("测试模型对象 StateLifecycle 的空安全与只读保护")
    void testStateLifecycleModel() {
        assertThatThrownBy(() -> new StateLifecycle<TestState, TestEvent, TestContext>(null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("state must not be null");

        StateLifecycle<TestState, TestEvent, TestContext> empty = new StateLifecycle<>(TestState.INIT, null, null);
        assertThat(empty.getState()).isEqualTo(TestState.INIT);
        assertThat(empty.getEntryActions()).isEmpty();
        assertThat(empty.getExitActions()).isEmpty();
        assertThat(empty.hasEntryActions()).isFalse();
        assertThat(empty.hasExitActions()).isFalse();

        // 验证返回列表不可变保护
        assertThatThrownBy(() -> empty.getEntryActions().add((from, to, event, ctx) -> {}))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
