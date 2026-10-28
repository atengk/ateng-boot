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
        super(SearchResultCode.SEARCH_PAGINATION_EXCEEDED,
                String.format("Requested pagination window [from + size = %d] exceeds the safety limit [%d]. Consider using searchAfter for deep scroll pagination.",
                        requestedWindow, maxResultWindow));
        this.requestedWindow = requestedWindow;
        this.maxResultWindow = maxResultWindow;
    }
}
