package io.github.atengk.boot.tcp.exception;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.core.exception.BusinessException;

/**
 * TCP 通信领域基础受控异常
 * <p>
 * 统一承载 TCP 通信在网络层、会话层及协议编解码层的语义化异常，保留因果链与错误码。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class TcpException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public TcpException(String msg) {
        super(CommonResultCode.INTERNAL_SERVER_ERROR, msg);
    }

    public TcpException(String msg, Throwable cause) {
        super(CommonResultCode.INTERNAL_SERVER_ERROR, msg, cause);
    }

    public TcpException(IResultCode resultCode, String msg) {
        super(resultCode, msg);
    }

    public TcpException(IResultCode resultCode, String msg, Throwable cause) {
        super(resultCode, msg, cause);
    }
}
