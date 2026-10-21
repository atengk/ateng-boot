package io.github.atengk.boot.core.exception;

import io.github.atengk.boot.core.code.CommonResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IdempotentException 单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("IdempotentException 单元测试")
class IdempotentExceptionTest {

    @Test
    @DisplayName("默认构造与自定义信息构造测试")
    void testConstructors() {
        IdempotentException exDefault = new IdempotentException();
        assertThat(exDefault.getCode()).isEqualTo(CommonResultCode.REPEAT_SUBMIT.getCode());
        assertThat(exDefault.getMessage()).isEqualTo(CommonResultCode.REPEAT_SUBMIT.getMsg());

        IdempotentException exCustom = new IdempotentException("自定义防重提示");
        assertThat(exCustom.getCode()).isEqualTo(CommonResultCode.REPEAT_SUBMIT.getCode());
        assertThat(exCustom.getMessage()).isEqualTo("自定义防重提示");

        Throwable cause = new RuntimeException("底层唯一键冲突");
        IdempotentException exCause = new IdempotentException("冲突异常", cause);
        assertThat(exCause.getCause()).isSameAs(cause);
    }
}
