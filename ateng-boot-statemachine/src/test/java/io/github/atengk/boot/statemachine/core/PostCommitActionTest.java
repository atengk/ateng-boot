package io.github.atengk.boot.statemachine.core;

import io.github.atengk.boot.statemachine.builder.StateMachineBuilder;
import io.github.atengk.boot.statemachine.builder.StateMachineBuilderFactory;
import io.github.atengk.boot.statemachine.model.TransitionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 事务提交后动作同步与不可逆通知单元测试
 *
 * @author Ateng
 * @since 2026-11-01
 */
class PostCommitActionTest {

    enum State {
        INIT, PROCESSING, SUCCESS, FAILED
    }

    enum Event {
        START, PASS, REJECT, RETRY
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("测试非事务环境下 postCommitAction 就地立即同步执行")
    void testNonTransactionalImmediateExecution() {
        List<String> logs = new ArrayList<>();

        StateMachineBuilder<State, Event, String> builder = StateMachineBuilderFactory.create();
        builder.externalTransition()
                .from(State.INIT)
                .to(State.PROCESSING)
                .on(Event.START)
                .perform((from, to, event, ctx) -> logs.add("TRANSITION_ACTION"))
                .performAfterCommit((from, to, event, ctx) -> logs.add("POST_COMMIT_ACTION:" + ctx));

        StateMachine<State, Event, String> machine = builder.build("NON_TX_MACHINE");

        State target = machine.fireEvent(State.INIT, Event.START, "ORDER_001");
        assertThat(target).isEqualTo(State.PROCESSING);
        assertThat(logs).containsExactly("TRANSITION_ACTION", "POST_COMMIT_ACTION:ORDER_001");
    }

    @Test
    @DisplayName("测试活跃 Spring 事务环境下 postCommitAction 仅在 afterCommit 后触发")
    void testActiveTransactionCommit() {
        TransactionSynchronizationManager.initSynchronization();

        List<String> logs = new ArrayList<>();

        StateMachineBuilder<State, Event, String> builder = StateMachineBuilderFactory.create();
        builder.externalTransition()
                .from(State.INIT)
                .to(State.PROCESSING)
                .on(Event.START)
                .perform((from, to, event, ctx) -> logs.add("MAIN_ACTION"))
                .performAfterCommit((from, to, event, ctx) -> logs.add("SEND_MQ_NOTICE:" + ctx));

        StateMachine<State, Event, String> machine = builder.build("TX_COMMIT_MACHINE");

        State target = machine.fireEvent(State.INIT, Event.START, "TX_001");
        assertThat(target).isEqualTo(State.PROCESSING);

        // 验证主动作已执行，但事务未提交前 postCommitAction 绝对未执行
        assertThat(logs).containsExactly("MAIN_ACTION");

        // 模拟 Spring 事务提交完成
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).isNotEmpty();
        for (TransactionSynchronization sync : synchronizations) {
            sync.afterCommit();
        }

        // 验证 afterCommit 后不可逆通知动作成功触发
        assertThat(logs).containsExactly("MAIN_ACTION", "SEND_MQ_NOTICE:TX_001");
    }

    @Test
    @DisplayName("测试事务回滚时 postCommitAction 严格取消执行（0 伪调用）")
    void testActiveTransactionRollback() {
        TransactionSynchronizationManager.initSynchronization();

        List<String> logs = new ArrayList<>();

        StateMachineBuilder<State, Event, String> builder = StateMachineBuilderFactory.create();
        builder.externalTransition()
                .from(State.INIT)
                .to(State.PROCESSING)
                .on(Event.START)
                .perform((from, to, event, ctx) -> logs.add("MAIN_ACTION"))
                .performAfterCommit((from, to, event, ctx) -> logs.add("GHOST_NOTIFICATION"));

        StateMachine<State, Event, String> machine = builder.build("TX_ROLLBACK_MACHINE");

        State target = machine.fireEvent(State.INIT, Event.START, "ROLLBACK_TX");
        assertThat(target).isEqualTo(State.PROCESSING);
        assertThat(logs).containsExactly("MAIN_ACTION");

        // 模拟事务回滚阶段
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        for (TransactionSynchronization sync : synchronizations) {
            sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        // 验证回滚后绝对未触发 postCommitAction，彻底杜绝幽灵通知
        assertThat(logs).containsExactly("MAIN_ACTION");
    }

    @Test
    @DisplayName("测试 postCommitAction 异常受控隔离：记录日志并通知拦截器，坚决不冲垮主流程")
    void testExceptionIsolationInPostCommitAction() {
        AtomicBoolean interceptorNotified = new AtomicBoolean(false);

        StateMachineBuilder<State, Event, String> builder = StateMachineBuilderFactory.create();
        builder.interceptor(new StateMachineInterceptor<State, Event, String>() {
            @Override
            public void onException(State sourceState, Event event, String context, Exception exception) {
                if (exception.getMessage().contains("第三方短信网关超时")) {
                    interceptorNotified.set(true);
                }
            }
        });

        builder.externalTransition()
                .from(State.INIT)
                .to(State.PROCESSING)
                .on(Event.START)
                .performAfterCommit((from, to, event, ctx) -> {
                    throw new RuntimeException("第三方短信网关超时");
                });

        StateMachine<State, Event, String> machine = builder.build("ISOLATION_MACHINE");

        // 1. Fail-Fast 模式：即使 postCommitAction 抛出异常，调用方也正常收到 targetState，不向外抛出异常
        assertThatCode(() -> {
            State target = machine.fireEvent(State.INIT, Event.START, "CTX");
            assertThat(target).isEqualTo(State.PROCESSING);
        }).doesNotThrowAnyException();

        assertThat(interceptorNotified.get()).isTrue();

        // 2. 静默探测模式：返回 accepted = true，异常不影响跃迁成功判定
        interceptorNotified.set(false);
        TransitionResult<State> quietResult = machine.fireEventQuietly(State.INIT, Event.START, "CTX");
        assertThat(quietResult.isAccepted()).isTrue();
        assertThat(quietResult.getTargetState()).isEqualTo(State.PROCESSING);
        assertThat(interceptorNotified.get()).isTrue();
    }

    @Test
    @DisplayName("测试四大构建器全面支持 performAfterCommit DSL（Internal、MultiFrom、Choice）")
    void testAllBuildersSupportPerformAfterCommit() {
        List<String> executionOrder = new ArrayList<>();

        StateMachineBuilder<State, Event, Integer> builder = StateMachineBuilderFactory.create();

        // 1. InternalTransitionBuilder
        builder.internalTransition()
                .from(State.PROCESSING)
                .on(Event.RETRY)
                .perform((from, to, ev, ctx) -> executionOrder.add("INTERNAL_ACTION"))
                .performAfterCommit(ctx -> executionOrder.add("INTERNAL_POST_COMMIT:" + ctx));

        // 2. MultiFromTransitionBuilder
        builder.multiFromTransition()
                .from(State.INIT, State.FAILED)
                .to(State.PROCESSING)
                .on(Event.START)
                .perform((from, to, ev, ctx) -> executionOrder.add("MULTI_FROM_ACTION"))
                .performAfterCommit(ctx -> executionOrder.add("MULTI_FROM_POST_COMMIT:" + ctx));

        // 3. ChoiceTransitionBuilder
        builder.choiceTransition()
                .from(State.PROCESSING)
                .on(Event.PASS)
                .when(score -> score != null && score >= 60).to(State.SUCCESS)
                .defaultTo(State.FAILED)
                .perform((from, to, ev, ctx) -> executionOrder.add("CHOICE_ACTION:" + to))
                .performAfterCommit(ctx -> executionOrder.add("CHOICE_POST_COMMIT:" + ctx));

        StateMachine<State, Event, Integer> machine = builder.build("ALL_BUILDERS_MACHINE");

        // 测试 Internal 自跃迁
        machine.fireEvent(State.PROCESSING, Event.RETRY, 1);
        assertThat(executionOrder).containsExactly("INTERNAL_ACTION", "INTERNAL_POST_COMMIT:1");
        executionOrder.clear();

        // 测试 MultiFrom 跃迁
        machine.fireEvent(State.FAILED, Event.START, 2);
        assertThat(executionOrder).containsExactly("MULTI_FROM_ACTION", "MULTI_FROM_POST_COMMIT:2");
        executionOrder.clear();

        // 测试 Choice 跃迁
        State choiceTarget = machine.fireEvent(State.PROCESSING, Event.PASS, 88);
        assertThat(choiceTarget).isEqualTo(State.SUCCESS);
        assertThat(executionOrder).containsExactly("CHOICE_ACTION:SUCCESS", "CHOICE_POST_COMMIT:88");
    }

    @Test
    @DisplayName("测试 CAS 乐观锁防重流程协同 postCommitAction")
    void testCasWithPostCommitAction() {
        AtomicInteger postCommitCount = new AtomicInteger(0);

        StateMachineBuilder<State, Event, String> builder = StateMachineBuilderFactory.create();
        builder.externalTransition()
                .from(State.INIT)
                .to(State.PROCESSING)
                .on(Event.START)
                .performAfterCommit(ctx -> postCommitCount.incrementAndGet());

        StateMachine<State, Event, String> machine = builder.build("CAS_POST_COMMIT_MACHINE");

        class Entity {
            State state = State.INIT;
        }
        Entity entity = new Entity();

        // 1. CAS 冲突（影响行数为 0）：postCommitAction 绝对不执行
        try {
            machine.fireEventWithCas(
                    entity,
                    e -> e.state,
                    (e, s) -> e.state = s,
                    Event.START,
                    "DATA",
                    (e, from, to) -> 0
            );
        } catch (Exception ignored) {
        }
        assertThat(postCommitCount.get()).isEqualTo(0);
        assertThat(entity.state).isEqualTo(State.INIT);

        // 2. CAS 成功：postCommitAction 成功执行
        State target = machine.fireEventWithCas(
                entity,
                e -> e.state,
                (e, s) -> e.state = s,
                Event.START,
                "DATA",
                (e, from, to) -> 1
        );
        assertThat(target).isEqualTo(State.PROCESSING);
        assertThat(entity.state).isEqualTo(State.PROCESSING);
        assertThat(postCommitCount.get()).isEqualTo(1);
    }
}
