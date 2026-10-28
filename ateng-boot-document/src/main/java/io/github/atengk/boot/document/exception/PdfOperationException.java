package io.github.atengk.boot.document.exception;

import io.github.atengk.boot.core.code.IResultCode;

/**
 * PDF 文档综合治理与操作异常
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class PdfOperationException extends DocumentException {

    private static final long serialVersionUID = 1L;

    public PdfOperationException(String msg) {
        super(DocumentResultCode.PDF_OPERATION_FAILED, msg);
    }

    public PdfOperationException(String msg, Throwable cause) {
        super(DocumentResultCode.PDF_OPERATION_FAILED, msg, cause);
    }

    public PdfOperationException(IResultCode resultCode, String msg) {
        super(resultCode, msg);
    }

    public PdfOperationException(IResultCode resultCode, String msg, Throwable cause) {
        super(resultCode, msg, cause);
    }
}
