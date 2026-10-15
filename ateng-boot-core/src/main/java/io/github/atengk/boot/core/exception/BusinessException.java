package io.github.atengk.boot.core.exception;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.code.IResultCode;
import lombok.Getter;

/**
 * 业务受控运行时异常体系
 *
 * @author Ateng
 * @since 2026-10-15
 */
@Getter
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private static final StackTraceElement[] EMPTY_STACK_TRACE = new StackTraceElement[0];

    /**
     * 业务状态码
     */
    private final Integer code;

    /**
     * 错误提示信息
     */
    private final String msg;

    /**
     * 绑定的状态码协议契约对象
     */
    private final IResultCode resultCode;

    /**
     * 基于预置状态码契约构造
     *
     * @param resultCode 状态码对象
     */
    public BusinessException(IResultCode resultCode) {
        super(resultCode != null ? resultCode.getMsg() : CommonResultCode.INTERNAL_SERVER_ERROR.getMsg());
        this.resultCode = resultCode;
        this.code = resultCode != null ? resultCode.getCode() : CommonResultCode.INTERNAL_SERVER_ERROR.getCode();
        this.msg = resultCode != null ? resultCode.getMsg() : CommonResultCode.INTERNAL_SERVER_ERROR.getMsg();
        setStackTrace(EMPTY_STACK_TRACE);
    }

    /**
     * 基于状态码契约与根因异常构造
     *
     * @param resultCode 状态码对象
     * @param cause      原始异常
     */
    public BusinessException(IResultCode resultCode, Throwable cause) {
        super(resultCode != null ? resultCode.getMsg() : CommonResultCode.INTERNAL_SERVER_ERROR.getMsg(), cause);
        this.resultCode = resultCode;
        this.code = resultCode != null ? resultCode.getCode() : CommonResultCode.INTERNAL_SERVER_ERROR.getCode();
        this.msg = resultCode != null ? resultCode.getMsg() : CommonResultCode.INTERNAL_SERVER_ERROR.getMsg();
        setStackTrace(EMPTY_STACK_TRACE);
    }

    /**
     * 基于自定义错误码与描述文案构造
     *
     * @param code 业务状态码
     * @param msg  提示信息
     */
    public BusinessException(Integer code, String msg) {
        super(msg);
        this.code = code;
        this.msg = msg;
        this.resultCode = null;
        setStackTrace(EMPTY_STACK_TRACE);
    }

    /**
     * 基于自定义错误码、描述文案与根因异常构造
     *
     * @param code  业务状态码
     * @param msg   提示信息
     * @param cause 原始异常
     */
    public BusinessException(Integer code, String msg, Throwable cause) {
        super(msg, cause);
        this.code = code;
        this.msg = msg;
        this.resultCode = null;
        setStackTrace(EMPTY_STACK_TRACE);
    }

    /**
     * 基于提示信息构造（默认业务错误码 500）
     *
     * @param msg 提示信息
     */
    public BusinessException(String msg) {
        this(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), msg);
    }

    /**
     * 重写抑制堆栈采集以优化运行时性能开销
     *
     * @return 当前异常实例
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
