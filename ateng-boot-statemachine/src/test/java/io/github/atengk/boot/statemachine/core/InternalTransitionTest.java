package io.github.atengk.boot.statemachine.core;

import io.github.atengk.boot.statemachine.builder.StateMachineBuilder;
import io.github.atengk.boot.statemachine.builder.StateMachineBuilderFactory;
import io.github.atengk.boot.statemachine.model.TransitionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内部自跃迁单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class InternalTransitionTest {

    enum Status {
        DELIVERING, FINISHED
    }

    enum Event {
        ADD_LOGISTICS_NOTE, COMPLETE
    }

    static class Context {
        private String note;
        private final List<String> history = new ArrayList<>();

        public Context(String note) {
            this.note = note;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }

        public List<String> getHistory() {
            return history;
        }
    }

    @Test
    @DisplayName("测试内部自跃迁状态不变且成功执行 Action")
    void testInternalTransition() {
        StateMachineBuilder<Status, Event, Context> builder = StateMachineBuilderFactory.create();
        builder.internalTransition()
                .from(Status.DELIVERING)
                .on(Event.ADD_LOGISTICS_NOTE)
                .when(ctx -> ctx.getNote() != null && !ctx.getNote().isEmpty())
                .perform((from, to, event, ctx) -> {
                    assertThat(from).isEqualTo(Status.DELIVERING);
                    assertThat(to).isEqualTo(Status.DELIVERING);
                    ctx.getHistory().add(ctx.getNote());
                });

        StateMachine<Status, Event, Context> machine = builder.build("LOGISTICS_MACHINE");

        Context ctx = new Context("包裹已到达分拨中心");
        Status target = machine.fireEvent(Status.DELIVERING, Event.ADD_LOGISTICS_NOTE, ctx);

        // 状态必须保持不变
        assertThat(target).isEqualTo(Status.DELIVERING);
        assertThat(ctx.getHistory()).containsExactly("包裹已到达分拨中心");

        // 静默探测
        Context ctx2 = new Context("包裹正在派送中");
        TransitionResult<Status> result = machine.fireEventQuietly(Status.DELIVERING, Event.ADD_LOGISTICS_NOTE, ctx2);
        assertThat(result.isAccepted()).isTrue();
        assertThat(result.getSourceState()).isEqualTo(Status.DELIVERING);
        assertThat(result.getTargetState()).isEqualTo(Status.DELIVERING);
        assertThat(ctx2.getHistory()).containsExactly("包裹正在派送中");
    }

    @Test
    @DisplayName("测试内部自跃迁条件拦截")
    void testInternalTransitionConditionBlock() {
        StateMachineBuilder<Status, Event, Context> builder = StateMachineBuilderFactory.create();
        builder.internalTransition()
                .from(Status.DELIVERING)
                .on(Event.ADD_LOGISTICS_NOTE)
                .when(ctx -> ctx.getNote() != null && !ctx.getNote().isEmpty());

        StateMachine<Status, Event, Context> machine = builder.build("LOGISTICS_MACHINE");

        Context ctx = new Context("");
        TransitionResult<Status> result = machine.fireEventQuietly(Status.DELIVERING, Event.ADD_LOGISTICS_NOTE, ctx);
        assertThat(result.isAccepted()).isFalse();
        assertThat(result.getFailureReason()).contains("前置条件卫语句校验不通过");
    }
}
