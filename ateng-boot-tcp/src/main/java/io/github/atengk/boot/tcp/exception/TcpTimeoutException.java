package io.github.atengk.boot.tcp.exception;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.code.IResultCode;

/**
 * TCP 全双工请求-响应 RPC 调用超时异常
 * <p>
 * 当请求发送后在指定 {@link java.time.Duration} 窗口内未收到对端关联回包时抛出。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class TcpTimeoutException extends TcpException {

    private static final long serialVersionUID = 1L;

    public TcpTimeoutException(String msg) {
        super(CommonResultCode.INTERNAL_SERVER_ERROR, msg);
    }

    public TcpTimeoutException(String msg, Throwable cause) {
        super(CommonResultCode.INTERNAL_SERVER_ERROR, msg, cause);
    }

    public TcpTimeoutException(IResultCode resultCode, String msg) {
        super(resultCode, msg);
    }

    public TcpTimeoutException(IResultCode resultCode, String msg, Throwable cause) {
        super(resultCode, msg, cause);
    }
}
