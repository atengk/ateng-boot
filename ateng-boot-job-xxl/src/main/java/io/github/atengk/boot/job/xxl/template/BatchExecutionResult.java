package io.github.atengk.boot.job.xxl.template;

import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 分片流式批处理执行结果统计
 *
 * <p>封装分片批处理的总读取数、成功数、失败数、批次计数、整体执行耗时、有界失败记录集合与截断标记。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class BatchExecutionResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 默认最大失败明细保留上限，防止大规模报错时发生 JVM 堆内存溢出 (OOM)
     */
    public static final int MAX_FAILED_ITEMS = 1000;

    /**
     * 总处理数据量（条数）
     */
    private final long totalCount;

    /**
     * 成功处理条数
     */
    private final long successCount;

    /**
     * 失败处理条数
     */
    private final long failCount;

    /**
     * 总切片批次数
     */
    private final int batchCount;

    /**
     * 执行总耗时
     */
    private final Duration duration;

    /**
     * 失败记录明细列表（有界集合，上限 1000 条）
     */
    private final List<Object> failedItems;

    /**
     * 失败记录是否因超过上限而被截断
     */
    private final boolean truncatedFailures;

    public BatchExecutionResult(long totalCount, long successCount, long failCount, int batchCount, Duration duration) {
        this(totalCount, successCount, failCount, batchCount, duration, Collections.emptyList(), false);
    }

    public BatchExecutionResult(long totalCount, long successCount, long failCount, int batchCount, Duration duration,
                                List<?> failedItems, boolean truncatedFailures) {
        this.totalCount = totalCount;
        this.successCount = successCount;
        this.failCount = failCount;
        this.batchCount = batchCount;
        this.duration = duration != null ? duration : Duration.ZERO;
        if (failedItems != null && !failedItems.isEmpty()) {
            if (failedItems.size() > MAX_FAILED_ITEMS) {
                this.failedItems = Collections.unmodifiableList(new ArrayList<>(failedItems.subList(0, MAX_FAILED_ITEMS)));
                this.truncatedFailures = true;
            } else {
                this.failedItems = Collections.unmodifiableList(new ArrayList<>(failedItems));
                this.truncatedFailures = truncatedFailures;
            }
        } else {
            this.failedItems = Collections.emptyList();
            this.truncatedFailures = truncatedFailures;
        }
    }

    /**
     * 是否全部记录处理成功（无失败记录）
     *
     * @return true 若失败数为 0
     */
    public boolean isSuccess() {
        return failCount == 0;
    }

    public long getTotalCount() {
        return totalCount;
    }

    public long getSuccessCount() {
        return successCount;
    }

    public long getFailCount() {
        return failCount;
    }

    public int getBatchCount() {
        return batchCount;
    }

    public Duration getDuration() {
        return duration;
    }

    /**
     * 获取失败记录明细列表（最大保留 1000 条，防御 JVM 堆内存溢出）
     *
     * @return 失败记录列表（不可变集合）
     */
    public List<Object> getFailedItems() {
        return failedItems;
    }

    /**
     * 泛型获取失败记录明细列表（强转便捷方法）
     *
     * @param <E> 实体类型
     * @return 失败记录列表
     */
    @SuppressWarnings("unchecked")
    public <E> List<E> getTypedFailedItems() {
        return (List<E>) failedItems;
    }

    /**
     * 失败记录是否因超过 1000 条上限而被截断
     *
     * @return true 若失败数超过 1000 条导致明细被截断
     */
    public boolean hasTruncatedFailures() {
        return truncatedFailures;
    }

    @Override
    public String toString() {
        return "BatchExecutionResult{" +
                "totalCount=" + totalCount +
                ", successCount=" + successCount +
                ", failCount=" + failCount +
                ", batchCount=" + batchCount +
                ", duration=" + duration.toMillis() + "ms" +
                ", failedItemsCount=" + failedItems.size() +
                ", truncatedFailures=" + truncatedFailures +
                '}';
    }
}
