package io.github.atengk.boot.job.snail.template;

import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 分布式批处理执行统计结果
 *
 * <p>封装分片批处理、MyBatis 流式处理或多租户并发执行的总处理条数、成功数、失败数、
 * 分批批次计数、整体执行耗时、有界失败分片清单及截断标记（默认上限 {@value #MAX_FAILED_ITEMS} 条）。</p>
 *
 * @param <K> 游标主键或租户标识类型
 * @author Ateng
 * @since 2026-10-31
 */
public class BatchExecutionResult<K> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 默认最大失败明细保留上限，防止海量异常风暴导致 JVM 堆内存溢出 (OOM)
     */
    public static final int MAX_FAILED_ITEMS = 500;

    /**
     * 总处理数据量（条数/租户数）
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
     * 失败批次明细清单（有界不可变集合）
     */
    private final List<FailedBatch<K>> failedBatches;

    /**
     * 失败明细是否因超出上限而被截断
     */
    private final boolean truncatedFailures;

    public BatchExecutionResult(long totalCount, long successCount, long failCount, int batchCount, Duration duration) {
        this(totalCount, successCount, failCount, batchCount, duration, Collections.emptyList(), MAX_FAILED_ITEMS, false);
    }

    public BatchExecutionResult(long totalCount, long successCount, long failCount, int batchCount, Duration duration, List<FailedBatch<K>> failedBatches) {
        this(totalCount, successCount, failCount, batchCount, duration, failedBatches, MAX_FAILED_ITEMS, false);
    }

    public BatchExecutionResult(
            long totalCount,
            long successCount,
            long failCount,
            int batchCount,
            Duration duration,
            List<FailedBatch<K>> failedBatches,
            boolean truncatedFailures) {
        this(totalCount, successCount, failCount, batchCount, duration, failedBatches, MAX_FAILED_ITEMS, truncatedFailures);
    }

    public BatchExecutionResult(
            long totalCount,
            long successCount,
            long failCount,
            int batchCount,
            Duration duration,
            List<FailedBatch<K>> failedBatches,
            int maxFailedItems,
            boolean truncatedFailures) {
        this.totalCount = totalCount;
        this.successCount = successCount;
        this.failCount = failCount;
        this.batchCount = batchCount;
        this.duration = duration != null ? duration : Duration.ZERO;
        int limit = maxFailedItems > 0 ? maxFailedItems : MAX_FAILED_ITEMS;
        if (failedBatches != null && !failedBatches.isEmpty()) {
            if (failedBatches.size() > limit) {
                this.failedBatches = Collections.unmodifiableList(new ArrayList<>(failedBatches.subList(0, limit)));
                this.truncatedFailures = true;
            } else {
                this.failedBatches = Collections.unmodifiableList(new ArrayList<>(failedBatches));
                this.truncatedFailures = truncatedFailures;
            }
        } else {
            this.failedBatches = Collections.emptyList();
            this.truncatedFailures = truncatedFailures;
        }
    }

    /**
     * 是否全部记录处理成功（无任何失败记录）
     *
     * @return true 若失败数为 0 且无失败分片
     */
    public boolean isSuccess() {
        return failCount == 0 && failedBatches.isEmpty();
    }

    /**
     * 是否存在失败记录或失败分片
     *
     * @return true 若存在失败记录或失败分片
     */
    public boolean hasFailures() {
        return failCount > 0 || !failedBatches.isEmpty();
    }

    public long getTotalCount() {
        return totalCount;
    }

    public long totalCount() {
        return totalCount;
    }

    public long getSuccessCount() {
        return successCount;
    }

    public long successCount() {
        return successCount;
    }

    public long getFailCount() {
        return failCount;
    }

    public long failCount() {
        return failCount;
    }

    public int getBatchCount() {
        return batchCount;
    }

    public int batchCount() {
        return batchCount;
    }

    public Duration getDuration() {
        return duration;
    }

    public Duration duration() {
        return duration;
    }

    /**
     * 获取失败分片清单
     *
     * @return 失败分片只读列表
     */
    public List<FailedBatch<K>> getFailedBatches() {
        return failedBatches;
    }

    /**
     * 获取失败分片清单 (流式风格)
     *
     * @return 失败分片只读列表
     */
    public List<FailedBatch<K>> failedBatches() {
        return failedBatches;
    }

    /**
     * 获取失败分片数量
     *
     * @return 失败分片个数
     */
    public int getFailedBatchCount() {
        return failedBatches.size();
    }

    /**
     * 获取失败分片数量 (流式风格)
     *
     * @return 失败分片个数
     */
    public int failedBatchCount() {
        return failedBatches.size();
    }

    /**
     * 失败分片清单是否因超过上限而被截断
     *
     * @return true 若发生截断
     */
    public boolean isTruncatedFailures() {
        return truncatedFailures;
    }

    /**
     * 失败分片清单是否因超过上限而被截断 (流式风格)
     *
     * @return true 若发生截断
     */
    public boolean truncatedFailures() {
        return truncatedFailures;
    }

    @Override
    public String toString() {
        return "BatchExecutionResult{" +
                "totalCount=" + totalCount +
                ", successCount=" + successCount +
                ", failCount=" + failCount +
                ", batchCount=" + batchCount +
                ", failedBatchCount=" + failedBatches.size() +
                ", truncatedFailures=" + truncatedFailures +
                ", duration=" + duration.toMillis() + "ms" +
                '}';
    }
}
