package io.github.atengk.boot.document.exception;

import io.github.atengk.boot.core.code.IResultCode;

/**
 * CJK 中文字体探测与加载异常
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class DocumentFontException extends DocumentException {

    private static final long serialVersionUID = 1L;

    public DocumentFontException(String msg) {
        super(DocumentResultCode.FONT_NOT_FOUND, msg);
    }

    public DocumentFontException(String msg, Throwable cause) {
        super(DocumentResultCode.FONT_NOT_FOUND, msg, cause);
    }

    public DocumentFontException(IResultCode resultCode, String msg) {
        super(resultCode, msg);
    }

    public DocumentFontException(IResultCode resultCode, String msg, Throwable cause) {
        super(resultCode, msg, cause);
    }
}
