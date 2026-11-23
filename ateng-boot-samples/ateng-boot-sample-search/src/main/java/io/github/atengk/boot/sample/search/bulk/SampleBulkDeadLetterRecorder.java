package io.github.atengk.boot.sample.search.bulk;

import io.github.atengk.boot.search.bulk.BulkDeadLetterHandler;
import io.github.atengk.boot.search.bulk.SearchBulkAction;
import io.github.atengk.boot.search.model.SearchBulkFailure;
import io.github.atengk.boot.search.model.SearchBulkFailureItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 样例自定义批量死信处理器 SPI 演示实现
 * <p>
 * 接管 {@code SearchBulkProcessor} 的整批异常死信与局部失败条目死信，
 * 维护线程安全的死信计数与最近明细摘要供样例管理端点观测验证。
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Slf4j
@Component
public class SampleBulkDeadLetterRecorder implements BulkDeadLetterHandler {

    private static final int MAX_RECENT_RECORDS = 50;

    private final AtomicLong batchFailureCount = new AtomicLong(0L);
    private final AtomicLong itemFailureCount = new AtomicLong(0L);
    private final ConcurrentLinkedDeque<String> recentRecords = new ConcurrentLinkedDeque<>();

    @Override
    public void onBatchFailure(long executionId, List<SearchBulkAction> actions, Throwable failure) {
        batchFailureCount.incrementAndGet();
        int count = actions != null ? actions.size() : 0;
        String summary = String.format("BATCH[exec=%d, actions=%d, error=%s]",
                executionId, count, failure != null ? failure.getMessage() : "unknown");
        appendRecord(summary);
        log.warn("[SampleDeadLetterRecorder] {}", summary);
    }

    @Override
    public void onItemFailure(long executionId, SearchBulkFailureItem item) {
        itemFailureCount.incrementAndGet();
        if (item == null || item.getFailure() == null) {
            return;
        }
        SearchBulkFailure f = item.getFailure();
        String actionType = item.getAction() != null && item.getAction().getType() != null
                ? item.getAction().getType().name()
                : "UNKNOWN";
        String summary = String.format("ITEM[exec=%d, action=%s, index=%s, id=%s, status=%s, reason=%s]",
                executionId, actionType, f.getIndex(), f.getId(), f.getStatus(), f.getErrorReason());
        appendRecord(summary);
        log.warn("[SampleDeadLetterRecorder] {}", summary);
    }

    private void appendRecord(String record) {
        recentRecords.addFirst(record);
        while (recentRecords.size() > MAX_RECENT_RECORDS) {
            recentRecords.pollLast();
        }
    }

    /**
     * 获取整批失败死信累计次数
     *
     * @return 整批失败次数
     */
    public long getBatchFailureCount() {
        return batchFailureCount.get();
    }

    /**
     * 获取局部文档失败死信累计次数
     *
     * @return 局部失败条目数
     */
    public long getItemFailureCount() {
        return itemFailureCount.get();
    }

    /**
     * 获取近期死信记录摘要列表（按最新倒序）
     *
     * @return 近期死信摘要列表
     */
    public List<String> getRecentRecords() {
        if (recentRecords.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(recentRecords));
    }

    /**
     * 清空死信统计与近期摘要记录
     */
    public void reset() {
        batchFailureCount.set(0L);
        itemFailureCount.set(0L);
        recentRecords.clear();
    }
}
