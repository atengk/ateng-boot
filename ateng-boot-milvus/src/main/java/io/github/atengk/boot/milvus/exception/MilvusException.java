package io.github.atengk.boot.milvus.exception;

import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.core.exception.BusinessException;

/**
 * Milvus 向量数据库专有业务异常
 *
 * @author Ateng
 * @since 2026-11-04
 */
public class MilvusException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public MilvusException(IResultCode resultCode) {
        super(resultCode);
    }

    public MilvusException(IResultCode resultCode, Throwable cause) {
        super(resultCode, cause);
    }

    public MilvusException(IResultCode resultCode, String customMsg) {
        super(resultCode, customMsg);
    }

    public MilvusException(IResultCode resultCode, String customMsg, Throwable cause) {
        super(resultCode, customMsg, cause);
    }

    public MilvusException(String message) {
        super(MilvusResultCode.OPERATION_FAILED, message);
    }

    public MilvusException(String message, Throwable cause) {
        super(MilvusResultCode.OPERATION_FAILED, message, cause);
    }
}
