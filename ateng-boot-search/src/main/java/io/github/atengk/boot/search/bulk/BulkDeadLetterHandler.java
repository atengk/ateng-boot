package io.github.atengk.boot.search.bulk;

import io.github.atengk.boot.search.model.SearchBulkFailure;
import io.github.atengk.boot.search.model.SearchBulkFailureItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 批量死信处理器扩展接口 (SPI)
 * <p>
 * 在 {@link SearchBulkProcessor} 遇到不可重试异常、瞬态故障重试耗尽，或 HTTP 200 OK 响应中包含局部失败条目时，
 * 接管受影响的原始操作与失败明细，以便业务侧执行死信落库、告警通知或补偿重放。
 *
 * @author Ateng
 * @since 2026-11-20
 */
public interface BulkDeadLetterHandler {

    /**
     * 默认受管日志死信处理器实现
     */
    BulkDeadLetterHandler LOGGING = new LoggingBulkDeadLetterHandler();

    /**
     * 当整批请求在重试耗尽或遭遇非瞬态致命异常时触发
     *
     * @param executionId 请求批次全局唯一单调递增 ID
     * @param actions     本批次包含的原始操作列表（只读视图）
     * @param failure     最终抛出的异常根因
     */
    default void onBatchFailure(long executionId, List<SearchBulkAction> actions, Throwable failure) {
    }

    /**
     * 当批量响应成功返回但存在单条文档局部失败（如 400 映射错误、409 版本冲突）时逐条触发
     *
     * @param executionId 请求批次全局唯一单调递增 ID
     * @param item        绑定原始操作与失败明细的死信条目
     */
    default void onItemFailure(long executionId, SearchBulkFailureItem item) {
    }

    /**
     * 默认基于 SLF4J 的受管日志死信处理器
     */
    final class LoggingBulkDeadLetterHandler implements BulkDeadLetterHandler {

        private static final Logger log = LoggerFactory.getLogger(LoggingBulkDeadLetterHandler.class);

        @Override
        public void onBatchFailure(long executionId, List<SearchBulkAction> actions, Throwable failure) {
            int actionCount = actions != null ? actions.size() : 0;
            String errorMsg = failure != null ? failure.getMessage() : "unknown";
            log.error("[SearchBulkDeadLetter] Batch [executionId={}] failed for {} action(s): {}",
                    executionId, actionCount, errorMsg, failure);
        }

        @Override
        public void onItemFailure(long executionId, SearchBulkFailureItem item) {
            if (item == null || item.getFailure() == null) {
                return;
            }
            SearchBulkFailure f = item.getFailure();
            SearchBulkAction action = item.getAction();
            String actionType = action != null && action.getType() != null ? action.getType().name() : "UNKNOWN";
            log.warn("[SearchBulkDeadLetter] Item failed in batch [executionId={}]: action={}, index={}, id={}, status={}, errorType={}, reason={}",
                    executionId, actionType, f.getIndex(), f.getId(), f.getStatus(), f.getErrorType(), f.getErrorReason());
        }
    }
}
