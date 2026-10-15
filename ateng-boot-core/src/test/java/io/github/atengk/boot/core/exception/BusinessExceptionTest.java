package io.github.atengk.boot.core.exception;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.code.IResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 业务异常核心体系测试
 *
 * @author Ateng
 * @since 2026-10-15
 */
@DisplayName("业务异常核心体系测试")
class BusinessExceptionTest {

    @Test
    @DisplayName("通过 IResultCode 构造业务异常")
    void shouldConstructWithResultCode() {
        BusinessException exception = new BusinessException(CommonResultCode.BAD_REQUEST);

        assertEquals(400, exception.getCode());
        assertEquals("参数校验失败", exception.getMsg());
        assertEquals("参数校验失败", exception.getMessage());
        assertEquals(CommonResultCode.BAD_REQUEST, exception.getResultCode());
    }

    @Test
    @DisplayName("通过自定义错误码和文案构造业务异常")
    void shouldConstructWithCustomCodeAndMsg() {
        BusinessException exception = new BusinessException(10001, "账户余额不足");

        assertEquals(10001, exception.getCode());
        assertEquals("账户余额不足", exception.getMsg());
        assertEquals("账户余额不足", exception.getMessage());
        assertNull(exception.getResultCode());
    }

    @Test
    @DisplayName("通过纯文本消息构造默认 500 异常")
    void shouldConstructWithDefault500Code() {
        BusinessException exception = new BusinessException("系统处理异常");

        assertEquals(500, exception.getCode());
        assertEquals("系统处理异常", exception.getMsg());
    }

    @Test
    @DisplayName("携带根因异常 Cause 链")
    void shouldPreserveCause() {
        IllegalArgumentException cause = new IllegalArgumentException("参数格式非法");
        BusinessException exception = new BusinessException(CommonResultCode.BAD_REQUEST, cause);

        assertEquals(cause, exception.getCause());
        assertEquals(400, exception.getCode());
    }

    @Test
    @DisplayName("验证 fillInStackTrace 被优化抑制以提升性能")
    void shouldOptimizeFillInStackTrace() {
        BusinessException exception = new BusinessException("无需堆栈追踪的业务中断");

        // 验证调用 fillInStackTrace 返回当前对象本身且不产生额外损耗
        assertSame(exception, exception.fillInStackTrace());
        assertEquals(0, exception.getStackTrace().length);
    }
}
