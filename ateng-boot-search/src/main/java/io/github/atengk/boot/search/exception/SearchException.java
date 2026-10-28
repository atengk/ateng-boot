package io.github.atengk.boot.search.exception;

import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.search.enums.SearchResultCode;

/**
 * 搜索模块顶层受管运行时异常
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class SearchException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public SearchException(String message) {
        super(SearchResultCode.SEARCH_CLIENT_ERROR, message);
    }

    public SearchException(IResultCode resultCode, String message) {
        super(resultCode, message);
    }

    public SearchException(IResultCode resultCode, String message, Throwable cause) {
        super(resultCode, message, cause);
    }

    public SearchException(String message, Throwable cause) {
        super(SearchResultCode.SEARCH_CLIENT_ERROR, message, cause);
    }
}
