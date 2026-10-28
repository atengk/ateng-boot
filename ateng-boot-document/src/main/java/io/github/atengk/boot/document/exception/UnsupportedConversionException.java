package io.github.atengk.boot.document.exception;

import io.github.atengk.boot.core.code.IResultCode;

/**
 * 不支持的文档格式转换异常
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class UnsupportedConversionException extends DocumentException {

    private static final long serialVersionUID = 1L;

    public UnsupportedConversionException(String msg) {
        super(DocumentResultCode.UNSUPPORTED_CONVERSION, msg);
    }

    public UnsupportedConversionException(String msg, Throwable cause) {
        super(DocumentResultCode.UNSUPPORTED_CONVERSION, msg, cause);
    }

    public UnsupportedConversionException(IResultCode resultCode, String msg) {
        super(resultCode, msg);
    }

    public UnsupportedConversionException(IResultCode resultCode, String msg, Throwable cause) {
        super(resultCode, msg, cause);
    }
}
