package io.github.atengk.boot.statemachine.core;

import io.github.atengk.boot.statemachine.builder.StateMachineBuilder;
import io.github.atengk.boot.statemachine.builder.StateMachineBuilderFactory;
import io.github.atengk.boot.statemachine.exception.StateMachineException;
import io.github.atengk.boot.statemachine.model.TransitionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 状态机切面拦截器 SPI 单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class StateMachineInterceptorTest {

    enum TestState {
        STATE_A, STATE_B
    }

    enum TestEvent {
        EVENT_GO
    }

    @Test
    @DisplayName("测试拦截器前置、后置执行时序与耗时感知")
    void testInterceptorSuccessLifecycle() {
        List<String> eventTrace = new ArrayList<>();

        StateMachineInterceptor<TestState, TestEvent, String> interceptor = new StateMachineInterceptor<>() {
            @Override
            public void beforeTransition(TestState from, TestEvent event, String context) {
                eventTrace.add("before:" + from + ":" + event);
            }

            @Override
            public void afterTransition(TestState from, TestState to, TestEvent event, String context, Duration elapsed) {
                eventTrace.add("after:" + from + "->" + to + ":" + (!elapsed.isNegative()));
            }

            @Override
            public void onException(TestState from, TestEvent event, String context, Exception ex) {
                eventTrace.add("error:" + ex.getMessage());
            }
        };

        StateMachineBuilder<TestState, TestEvent, String> builder = StateMachineBuilderFactory.create();
        builder.interceptor(interceptor);
        builder.externalTransition()
                .from(TestState.STATE_A)
                .to(TestState.STATE_B)
                .on(TestEvent.EVENT_GO)
                .perform((f, t, e, c) -> eventTrace.add("action:" + f + "->" + t));

        StateMachine<TestState, TestEvent, String> machine = builder.build("INTERCEPTOR_TEST");

        TestState target = machine.fireEvent(TestState.STATE_A, TestEvent.EVENT_GO, "ctx");
        assertThat(target).isEqualTo(TestState.STATE_B);

        assertThat(eventTrace).containsExactly(
                "before:STATE_A:EVENT_GO",
                "action:STATE_A->STATE_B",
                "after:STATE_A->STATE_B:true"
        );
    }

    @Test
    @DisplayName("测试跃迁动作异常时触发 onException 拦截钩子")
    void testInterceptorOnException() {
        List<String> eventTrace = new ArrayList<>();

        StateMachineInterceptor<TestState, TestEvent, String> interceptor = new StateMachineInterceptor<>() {
            @Override
            public void beforeTransition(TestState from, TestEvent event, String context) {
                eventTrace.add("before");
            }

            @Override
            public void afterTransition(TestState from, TestState to, TestEvent event, String context, Duration elapsed) {
                eventTrace.add("after");
            }

            @Override
            public void onException(TestState from, TestEvent event, String context, Exception ex) {
                eventTrace.add("onException:" + ex.getClass().getSimpleName());
            }
        };

        StateMachineBuilder<TestState, TestEvent, String> builder = StateMachineBuilderFactory.create();
        builder.interceptor(interceptor);
        builder.externalTransition()
                .from(TestState.STATE_A)
                .to(TestState.STATE_B)
                .on(TestEvent.EVENT_GO)
                .perform((f, t, e, c) -> {
                    throw new IllegalStateException("Simulated Action Error");
                });

        StateMachine<TestState, TestEvent, String> machine = builder.build("INTERCEPTOR_ERR_TEST");

        assertThatThrownBy(() -> machine.fireEvent(TestState.STATE_A, TestEvent.EVENT_GO, "ctx"))
                .isInstanceOf(StateMachineException.class);

        assertThat(eventTrace).containsExactly(
                "before",
                "onException:StateMachineException"
        );
    }

    @Test
    @DisplayName("测试静默模式下拦截器生命周期完整触发")
    void testInterceptorQuietlyLifecycle() {
        List<String> eventTrace = new ArrayList<>();

        StateMachineInterceptor<TestState, TestEvent, String> interceptor = new StateMachineInterceptor<>() {
            @Override
            public void beforeTransition(TestState from, TestEvent event, String context) {
                eventTrace.add("before:" + from);
            }

            @Override
            public void afterTransition(TestState from, TestState to, TestEvent event, String context, Duration elapsed) {
                eventTrace.add("after:" + to);
            }

            @Override
            public void onException(TestState from, TestEvent event, String context, Exception ex) {
                eventTrace.add("onException:" + from);
            }
        };

        StateMachineBuilder<TestState, TestEvent, String> builder = StateMachineBuilderFactory.create();
        builder.interceptor(interceptor);
        builder.externalTransition()
                .from(TestState.STATE_A)
                .to(TestState.STATE_B)
                .on(TestEvent.EVENT_GO);

        StateMachine<TestState, TestEvent, String> machine = builder.build("INTERCEPTOR_QUIET_TEST");

        // 1. 成功探测
        TransitionResult<TestState> resultSuccess = machine.fireEventQuietly(TestState.STATE_A, TestEvent.EVENT_GO, "ctx");
        assertThat(resultSuccess.isAccepted()).isTrue();
        assertThat(eventTrace).containsExactly("before:STATE_A", "after:STATE_B");

        eventTrace.clear();

        // 2. 失败探测 (无对应路由)
        TransitionResult<TestState> resultFail = machine.fireEventQuietly(TestState.STATE_B, TestEvent.EVENT_GO, "ctx");
        assertThat(resultFail.isAccepted()).isFalse();
        assertThat(eventTrace).containsExactly("before:STATE_B", "onException:STATE_B");
    }

    @Test
    @DisplayName("测试通过 addInterceptor 动态追加拦截器")
    void testAddInterceptorDynamically() {
        StateMachineBuilder<TestState, TestEvent, String> builder = StateMachineBuilderFactory.create();
        builder.externalTransition()
                .from(TestState.STATE_A)
                .to(TestState.STATE_B)
                .on(TestEvent.EVENT_GO);

        StateMachine<TestState, TestEvent, String> machine = builder.build("DYNAMIC_INTERCEPTOR_TEST");
        assertThat(machine).isInstanceOf(DefaultStateMachine.class);

        DefaultStateMachine<TestState, TestEvent, String> defaultMachine = (DefaultStateMachine<TestState, TestEvent, String>) machine;
        assertThat(defaultMachine.getInterceptors()).isEmpty();

        List<String> logs = new ArrayList<>();
        defaultMachine.addInterceptor(new StateMachineInterceptor<>() {
            @Override
            public void beforeTransition(TestState from, TestEvent event, String context) {
                logs.add("dynamic-before");
            }
        });

        assertThat(defaultMachine.getInterceptors()).hasSize(1);
        machine.fireEvent(TestState.STATE_A, TestEvent.EVENT_GO, "ctx");
        assertThat(logs).containsExactly("dynamic-before");
    }
}
