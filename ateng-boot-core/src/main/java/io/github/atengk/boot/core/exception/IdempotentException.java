package io.github.atengk.boot.core.exception;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.code.IResultCode;

/**
 * 跨中间件与业务防重幂等拦截异常
 * 当接口或消息监听器检测到重复提交、重复消息投递或业务唯一键冲突时抛出
 *
 * @author Ateng
 * @since 2026-10-21
 */
public class IdempotentException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public IdempotentException(String message) {
        super(CommonResultCode.REPEAT_SUBMIT.getCode(), message);
    }

    public IdempotentException() {
        super(CommonResultCode.REPEAT_SUBMIT);
    }

    public IdempotentException(String message, Throwable cause) {
        super(CommonResultCode.REPEAT_SUBMIT.getCode(), message, cause, cause != null);
    }

    public IdempotentException(IResultCode resultCode, String message) {
        super(resultCode, message);
    }
}
