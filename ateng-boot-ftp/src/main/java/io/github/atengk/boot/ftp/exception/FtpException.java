package io.github.atengk.boot.ftp.exception;

import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.core.exception.BusinessException;

/**
 * FTP 远程传输模块受管业务异常基类
 *
 * @author Ateng
 * @since 2026-10-22
 */
public class FtpException extends BusinessException {

    private static final long serialVersionUID = 1L;

    /**
     * 基于状态码契约构造异常
     *
     * @param resultCode 状态码对象
     */
    public FtpException(IResultCode resultCode) {
        super(resultCode);
    }

    /**
     * 基于状态码契约与根因异常构造（保留因果链）
     *
     * @param resultCode 状态码对象
     * @param cause      原始异常
     */
    public FtpException(IResultCode resultCode, Throwable cause) {
        super(resultCode, cause);
    }

    /**
     * 基于状态码契约与自定义提示信息构造
     *
     * @param resultCode 状态码对象
     * @param msg        自定义提示信息
     */
    public FtpException(IResultCode resultCode, String msg) {
        super(resultCode, msg);
    }

    /**
     * 基于状态码契约、自定义提示信息与根因异常构造（完整保留因果链）
     *
     * @param resultCode 状态码对象
     * @param msg        自定义提示信息
     * @param cause      原始异常
     */
    public FtpException(IResultCode resultCode, String msg, Throwable cause) {
        super(resolveResultCode(resultCode), msg, cause);
    }

    private static IResultCode resolveResultCode(IResultCode resultCode) {
        return resultCode != null ? resultCode : FtpResultCode.OPERATION_FAILED;
    }
}
