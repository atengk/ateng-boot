package io.github.atengk.boot.tcp.exception;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.code.IResultCode;

/**
 * TCP 出站缓冲区背压异常
 * <p>
 * 当底层 Netty 网络通道出站缓冲区达到高水位（isWritable == false）且无法在背压策略允许范围内发送时抛出。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-02
 */
public class TcpBackpressureException extends TcpException {

    private static final long serialVersionUID = 1L;

    public TcpBackpressureException(String msg) {
        super(CommonResultCode.SERVICE_UNAVAILABLE, msg);
    }

    public TcpBackpressureException(String msg, Throwable cause) {
        super(CommonResultCode.SERVICE_UNAVAILABLE, msg, cause);
    }

    public TcpBackpressureException(IResultCode resultCode, String msg) {
        super(resultCode, msg);
    }

    public TcpBackpressureException(IResultCode resultCode, String msg, Throwable cause) {
        super(resultCode, msg, cause);
    }
}
