package io.github.atengk.boot.document.exception;

import io.github.atengk.boot.core.code.IResultCode;

/**
 * Word 文档模板渲染与拼装异常
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class WordRenderException extends DocumentException {

    private static final long serialVersionUID = 1L;

    public WordRenderException(String msg) {
        super(DocumentResultCode.WORD_RENDER_FAILED, msg);
    }

    public WordRenderException(String msg, Throwable cause) {
        super(DocumentResultCode.WORD_RENDER_FAILED, msg, cause);
    }

    public WordRenderException(IResultCode resultCode, String msg) {
        super(resultCode, msg);
    }

    public WordRenderException(IResultCode resultCode, String msg, Throwable cause) {
        super(resultCode, msg, cause);
    }
}
