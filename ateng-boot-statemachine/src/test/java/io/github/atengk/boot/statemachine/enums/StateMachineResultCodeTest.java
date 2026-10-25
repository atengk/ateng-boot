package io.github.atengk.boot.statemachine.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 状态机结果码单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class StateMachineResultCodeTest {

    @Test
    @DisplayName("测试状态机结果码枚举契约")
    void testResultCodes() {
        assertThat(StateMachineResultCode.TRANSITION_NOT_FOUND.getCode()).isEqualTo(59001);
        assertThat(StateMachineResultCode.TRANSITION_NOT_FOUND.getMsg()).isEqualTo("未找到合法的状态跃迁路由");

        assertThat(StateMachineResultCode.CONDITION_FAILED.getCode()).isEqualTo(59002);
        assertThat(StateMachineResultCode.CONDITION_FAILED.getMsg()).isEqualTo("状态跃迁前置条件不满足");

        assertThat(StateMachineResultCode.ACTION_EXECUTION_FAILED.getCode()).isEqualTo(59003);
        assertThat(StateMachineResultCode.ACTION_EXECUTION_FAILED.getMsg()).isEqualTo("状态跃迁动作执行失败");

        assertThat(StateMachineResultCode.DUPLICATE_MACHINE_ID.getCode()).isEqualTo(59004);
        assertThat(StateMachineResultCode.DUPLICATE_MACHINE_ID.getMsg()).isEqualTo("状态机标识重复定义");
    }
}
