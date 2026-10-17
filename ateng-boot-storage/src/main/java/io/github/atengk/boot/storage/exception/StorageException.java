package io.github.atengk.boot.storage.exception;

import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.core.exception.BusinessException;

/**
 * 存储模块受管业务异常基类
 *
 * @author Ateng
 * @since 2026-10-17
 */
public class StorageException extends BusinessException {

    private static final long serialVersionUID = 1L;

    /**
     * 基于存储状态码构造异常
     *
     * @param resultCode 状态码对象
     */
    public StorageException(IResultCode resultCode) {
        super(resultCode);
    }

    /**
     * 基于存储状态码与根因异常构造
     *
     * @param resultCode 状态码对象
     * @param cause      原始异常
     */
    public StorageException(IResultCode resultCode, Throwable cause) {
        super(resultCode, cause);
    }

    /**
     * 基于存储状态码与自定义提示信息构造
     *
     * @param resultCode 状态码对象
     * @param msg        自定义提示信息
     */
    public StorageException(IResultCode resultCode, String msg) {
        super(resultCode, msg);
    }

    /**
     * 基于存储状态码、自定义提示信息与根因异常构造
     *
     * @param resultCode 状态码对象
     * @param msg        自定义提示信息
     * @param cause      原始异常
     */
    public StorageException(IResultCode resultCode, String msg, Throwable cause) {
        super(resultCode != null ? resultCode.getCode() : StorageResultCode.OPERATION_FAILED.getCode(),
                msg != null ? msg : (resultCode != null ? resultCode.getMsg() : StorageResultCode.OPERATION_FAILED.getMsg()),
                cause);
    }

    /**
     * 基于自定义提示文案构造（缺省使用 OPERATION_FAILED 状态码）
     *
     * @param msg 提示信息
     */
    public StorageException(String msg) {
        super(StorageResultCode.OPERATION_FAILED, msg);
    }

    /**
     * 基于自定义提示文案与根因异常构造（缺省使用 OPERATION_FAILED 状态码）
     *
     * @param msg   提示信息
     * @param cause 原始异常
     */
    public StorageException(String msg, Throwable cause) {
        this(StorageResultCode.OPERATION_FAILED, msg, cause);
    }

    /**
     * 基于自定义状态码与提示信息构造
     *
     * @param code 状态码数值
     * @param msg  提示信息
     */
    public StorageException(Integer code, String msg) {
        super(code, msg);
    }

    /**
     * 基于自定义状态码、提示信息与根因异常构造
     *
     * @param code  状态码数值
     * @param msg   提示信息
     * @param cause 原始异常
     */
    public StorageException(Integer code, String msg, Throwable cause) {
        super(code, msg, cause);
    }
}
