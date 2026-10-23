package io.github.atengk.boot.document.exception;

import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.core.exception.BusinessException;

/**
 * 文档治理模块受管业务异常基类
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class DocumentException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public DocumentException(IResultCode resultCode) {
        super(resolveResultCode(resultCode));
    }

    public DocumentException(IResultCode resultCode, Throwable cause) {
        super(resolveResultCode(resultCode), cause);
    }

    public DocumentException(IResultCode resultCode, String msg) {
        super(resolveResultCode(resultCode), msg);
    }

    public DocumentException(IResultCode resultCode, String msg, Throwable cause) {
        super(resolveResultCode(resultCode), msg, cause);
    }

    public DocumentException(String msg) {
        super(DocumentResultCode.DOCUMENT_OPERATION_FAILED, msg);
    }

    public DocumentException(String msg, Throwable cause) {
        super(DocumentResultCode.DOCUMENT_OPERATION_FAILED, msg, cause);
    }

    private static IResultCode resolveResultCode(IResultCode resultCode) {
        return resultCode != null ? resultCode : DocumentResultCode.DOCUMENT_OPERATION_FAILED;
    }
}
