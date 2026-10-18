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

    private final boolean writableStackTrace;

    /**
     * 基于预置状态码契约构造（默认无堆栈轻量模式）
     *
     * @param resultCode 状态码对象
     */
    public BusinessException(IResultCode resultCode) {
        this(resultCode, null, null, false);
    }

    /**
     * 基于状态码契约与根因异常构造（默认保留堆栈以保障因果链可追踪）
     *
     * @param resultCode 状态码对象
     * @param cause      原始异常
     */
    public BusinessException(IResultCode resultCode, Throwable cause) {
        this(resultCode, null, cause, cause != null);
    }

    /**
     * 基于状态码契约与自定义提示信息构造（默认无堆栈轻量模式）
     *
     * @param resultCode 状态码对象
     * @param msg        自定义提示信息
     */
    public BusinessException(IResultCode resultCode, String msg) {
        this(resultCode, msg, null, false);
    }

    /**
     * 基于状态码契约、自定义提示信息与根因异常构造（默认保留堆栈）
     *
     * @param resultCode 状态码对象
     * @param msg        自定义提示信息
     * @param cause      原始异常
     */
    public BusinessException(IResultCode resultCode, String msg, Throwable cause) {
        this(resultCode, msg, cause, cause != null);
    }

    /**
     * 基于状态码契约、自定义提示信息、根因异常与可写堆栈开关全量构造
     *
     * @param resultCode         状态码对象
     * @param msg                自定义提示信息
     * @param cause              原始异常
     * @param writableStackTrace 是否写入并保留调用栈轨迹
     */
    public BusinessException(IResultCode resultCode, String msg, Throwable cause, boolean writableStackTrace) {
        this(resultCode != null ? resultCode.getCode() : CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                resolveMessage(resultCode, msg),
                resultCode,
                cause,
                writableStackTrace);
    }

    /**
     * 基于自定义错误码与描述文案构造（默认无堆栈轻量模式）
     *
     * @param code 业务状态码
     * @param msg  提示信息
     */
    public BusinessException(Integer code, String msg) {
        this(code, msg, null, false);
    }

    /**
     * 基于自定义错误码、描述文案与根因异常构造（默认保留堆栈以保障因果链可追踪）
     *
     * @param code  业务状态码
     * @param msg   提示信息
     * @param cause 原始异常
     */
    public BusinessException(Integer code, String msg, Throwable cause) {
        this(code, msg, cause, cause != null);
    }

    /**
     * 基于自定义错误码、描述文案、根因异常与可写堆栈开关全量构造
     *
     * @param code               业务状态码
     * @param msg                提示信息
     * @param cause              原始异常
     * @param writableStackTrace 是否写入并保留调用栈轨迹
     */
    public BusinessException(Integer code, String msg, Throwable cause, boolean writableStackTrace) {
        this(code, msg, null, cause, writableStackTrace);
    }

    /**
     * 基于提示信息构造（默认业务错误码 500，无堆栈轻量模式，绑定预置契约）
     *
     * @param msg 提示信息
     */
    public BusinessException(String msg) {
        this(CommonResultCode.INTERNAL_SERVER_ERROR, msg);
    }

    /**
     * 基于提示信息与根因异常构造（默认业务错误码 500，保留堆栈，绑定预置契约）
     *
     * @param msg   提示信息
     * @param cause 原始异常
     */
    public BusinessException(String msg, Throwable cause) {
        this(CommonResultCode.INTERNAL_SERVER_ERROR, msg, cause);
    }

    private BusinessException(Integer code, String msg, IResultCode resultCode, Throwable cause, boolean writableStackTrace) {
        super(msg, cause, true, writableStackTrace);
        this.code = code;
        this.msg = msg;
        this.resultCode = resultCode;
        this.writableStackTrace = writableStackTrace;
        if (writableStackTrace) {
            super.fillInStackTrace();
        } else {
            setStackTrace(EMPTY_STACK_TRACE);
        }
    }

    private static String resolveMessage(IResultCode resultCode, String customMsg) {
        if (customMsg != null) {
            return customMsg;
        }
        return resultCode != null ? resultCode.getMsg() : CommonResultCode.INTERNAL_SERVER_ERROR.getMsg();
    }

    /**
     * 依据可写堆栈标志决定是否抑制或填充堆栈采集
     *
     * @return 当前异常实例
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this.writableStackTrace ? super.fillInStackTrace() : this;
    }
}
