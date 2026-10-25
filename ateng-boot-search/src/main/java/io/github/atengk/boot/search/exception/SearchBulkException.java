package io.github.atengk.boot.search.exception;

import io.github.atengk.boot.search.enums.SearchResultCode;
import io.github.atengk.boot.search.model.SearchBulkFailure;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * 搜索引擎批量写入局部失败受管业务异常
 * <p>
 * 当批量操作处于严格快速失败模式（如 indexBatch）且底层存在失败文档时抛出，
 * 封装失败条目快照及首条失败根因摘要，防止脏数据静默扩散。
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
public class SearchBulkException extends SearchException {

    private static final long serialVersionUID = 1L;

    /**
     * 批量操作失败文档快照集合
     */
    private final List<SearchBulkFailure> failures;

    /**
     * 成功写入的文档数量
     */
    private final int successCount;

    /**
     * 写入失败的文档数量
     */
    private final int failureCount;

    public SearchBulkException(String message, List<SearchBulkFailure> failures, int successCount, int failureCount) {
        super(SearchResultCode.SEARCH_BULK_PARTIAL_ERROR, message);
        this.failures = failures != null ? Collections.unmodifiableList(failures) : Collections.emptyList();
        this.successCount = successCount;
        this.failureCount = failureCount;
    }

    public SearchBulkException(String message, List<SearchBulkFailure> failures, int successCount, int failureCount, Throwable cause) {
        super(SearchResultCode.SEARCH_BULK_PARTIAL_ERROR, message, cause);
        this.failures = failures != null ? Collections.unmodifiableList(failures) : Collections.emptyList();
        this.successCount = successCount;
        this.failureCount = failureCount;
    }
}
