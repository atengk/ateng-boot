package io.github.atengk.boot.search.exception;

import io.github.atengk.boot.search.enums.SearchResultCode;
import lombok.Getter;

/**
 * 搜索引擎文档版本冲突异常（乐观并发控制拦截）
 * <p>
 * 当执行基于 seqNo/primaryTerm 或 version 的条件更新/写入时，若服务端文档已变更则抛出此异常。
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
public class SearchVersionConflictException extends SearchException {

    private static final long serialVersionUID = 1L;

    /**
     * 期望的序列号
     */
    private final Long expectedSeqNo;

    /**
     * 期望的分片世代
     */
    private final Long expectedPrimaryTerm;

    public SearchVersionConflictException(String message) {
        super(SearchResultCode.SEARCH_VERSION_CONFLICT, message);
        this.expectedSeqNo = null;
        this.expectedPrimaryTerm = null;
    }

    public SearchVersionConflictException(String message, Throwable cause) {
        super(SearchResultCode.SEARCH_VERSION_CONFLICT, message, cause);
        this.expectedSeqNo = null;
        this.expectedPrimaryTerm = null;
    }

    public SearchVersionConflictException(String message, Long expectedSeqNo, Long expectedPrimaryTerm, Throwable cause) {
        super(SearchResultCode.SEARCH_VERSION_CONFLICT, message, cause);
        this.expectedSeqNo = expectedSeqNo;
        this.expectedPrimaryTerm = expectedPrimaryTerm;
    }
}
