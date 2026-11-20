package io.github.atengk.boot.search.exception;

import io.github.atengk.boot.search.enums.SearchResultCode;
import lombok.Getter;

/**
 * 浅分页窗口超出安全阈值刚性熔断异常
 * <p>
 * 当浅分页请求参数 (from + size) 超过集群设定的最大窗口深度 (max_result_window，默认 10,000) 时触发 Fail-Fast 阻断，
 * 从根源杜绝高深度浅分页引发的协调节点堆内存膨胀与 Full GC 崩溃。
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
public class SearchPaginationExceededException extends SearchException {

    private final int requestedWindow;
    private final int maxResultWindow;

    public SearchPaginationExceededException(int requestedWindow, int maxResultWindow) {
        this(requestedWindow, maxResultWindow,
                String.format("Requested pagination window [from + size = %d] exceeds the safety limit [%d]. Consider using searchAfter for deep scroll pagination.",
                        requestedWindow, maxResultWindow));
    }

    public SearchPaginationExceededException(int requestedWindow, int maxResultWindow, String message) {
        super(SearchResultCode.SEARCH_PAGINATION_EXCEEDED, message);
        this.requestedWindow = requestedWindow;
        this.maxResultWindow = maxResultWindow;
    }

    /**
     * 构造深度游标分页单页条数超限异常
     *
     * @param requestedSize      请求的每页大小
     * @param maxSearchAfterSize 允许的单页上限
     * @return SearchPaginationExceededException
     */
    public static SearchPaginationExceededException forSearchAfter(int requestedSize, int maxSearchAfterSize) {
        return new SearchPaginationExceededException(requestedSize, maxSearchAfterSize,
                String.format("Requested searchAfter page size [%d] exceeds the safety limit [%d].",
                        requestedSize, maxSearchAfterSize));
    }
}
