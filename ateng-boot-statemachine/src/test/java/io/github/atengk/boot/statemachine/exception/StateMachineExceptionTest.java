package io.github.atengk.boot.statemachine.exception;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.statemachine.enums.StateMachineResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 状态机受管异常单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class StateMachineExceptionTest {

    @Test
    @DisplayName("测试异常基础构造与字段承载")
    void testBasicConstructors() {
        StateMachineException ex1 = new StateMachineException("Transition not found");
        assertThat(ex1).isInstanceOf(BusinessException.class);
        assertThat(ex1.getCode()).isEqualTo(StateMachineResultCode.TRANSITION_NOT_FOUND.getCode());
        assertThat(ex1.getMessage()).isEqualTo("Transition not found");

        RuntimeException cause = new RuntimeException("DB error");
        StateMachineException ex2 = new StateMachineException("Action failed", cause);
        assertThat(ex2.getCode()).isEqualTo(StateMachineResultCode.ACTION_EXECUTION_FAILED.getCode());
        assertThat(ex2.getCause()).isEqualTo(cause);

        StateMachineException ex3 = new StateMachineException(StateMachineResultCode.CONDITION_FAILED);
        assertThat(ex3.getCode()).isEqualTo(StateMachineResultCode.CONDITION_FAILED.getCode());
        assertThat(ex3.getMessage()).isEqualTo(StateMachineResultCode.CONDITION_FAILED.getMsg());
    }

    @Test
    @DisplayName("测试携带源状态、目标状态与事件的结构化异常")
    void testDetailedConstructors() {
        StateMachineException ex = new StateMachineException(
                StateMachineResultCode.TRANSITION_NOT_FOUND,
                "ORDER_INIT",
                "ORDER_PAID",
                "PAY_EVENT",
                "非法跃迁",
                new IllegalStateException("Invalid state")
        );

        assertThat(ex.getCode()).isEqualTo(59001);
        assertThat(ex.getSourceState()).isEqualTo("ORDER_INIT");
        assertThat(ex.getTargetState()).isEqualTo("ORDER_PAID");
        assertThat(ex.getEvent()).isEqualTo("PAY_EVENT");
        assertThat(ex.getMessage()).isEqualTo("非法跃迁");
        assertThat(ex.getCause()).isInstanceOf(IllegalStateException.class);

        StateMachineException exWithoutCause = new StateMachineException(
                StateMachineResultCode.CONCURRENT_CAS_CONFLICT,
                "WAIT_PAY",
                "PAID",
                "PAY_EVENT",
                "并发冲突"
        );
        assertThat(exWithoutCause.getCode()).isEqualTo(59005);
        assertThat(exWithoutCause.getSourceState()).isEqualTo("WAIT_PAY");
        assertThat(exWithoutCause.getTargetState()).isEqualTo("PAID");
        assertThat(exWithoutCause.getEvent()).isEqualTo("PAY_EVENT");
        assertThat(exWithoutCause.getMessage()).isEqualTo("并发冲突");
        assertThat(exWithoutCause.getCause()).isNull();
    }
}
