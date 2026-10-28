package io.github.atengk.boot.statemachine.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跃迁结果承载对象单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class TransitionResultTest {

    @Test
    @DisplayName("测试成功结果构建")
    void testSuccess() {
        TransitionResult<String> result = TransitionResult.success("INIT", "PAID", "PAY_SUCCESS");
        assertThat(result.isAccepted()).isTrue();
        assertThat(result.getSourceState()).isEqualTo("INIT");
        assertThat(result.getTargetState()).isEqualTo("PAID");
        assertThat(result.getEvent()).isEqualTo("PAY_SUCCESS");
        assertThat(result.getFailureReason()).isNull();
        assertThat(result.getCause()).isNull();
    }

    @Test
    @DisplayName("测试失败结果构建")
    void testFailure() {
        TransitionResult<String> result = TransitionResult.failure("INIT", "SHIP_EVENT", "未找到合法路由");
        assertThat(result.isAccepted()).isFalse();
        assertThat(result.getSourceState()).isEqualTo("INIT");
        assertThat(result.getTargetState()).isEqualTo("INIT");
        assertThat(result.getEvent()).isEqualTo("SHIP_EVENT");
        assertThat(result.getFailureReason()).isEqualTo("未找到合法路由");
        assertThat(result.getCause()).isNull();

        IllegalArgumentException ex = new IllegalArgumentException("Condition fail");
        TransitionResult<String> resultWithCause = TransitionResult.failure("INIT", "SHIP_EVENT", "条件不满足", ex);
        assertThat(resultWithCause.isAccepted()).isFalse();
        assertThat(resultWithCause.getCause()).isSameAs(ex);
    }
}
