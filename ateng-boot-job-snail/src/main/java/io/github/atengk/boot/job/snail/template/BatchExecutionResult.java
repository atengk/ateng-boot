package io.github.atengk.boot.job.snail.template;

import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 分布式批处理执行统计结果
 *
 * <p>封装分片批处理或多租户遍历执行的总处理条数、成功数、失败数、分批批次计数、整体执行耗时以及失败分片详情。</p>
 *
 * @param <K> 游标主键类型
 * @author Ateng
 * @since 2026-10-31
 */
public class BatchExecutionResult<K> implements Serializable {

    private static final long serialVersionUID = 1L;

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
     * 失败批次明细清单
     */
    private final List<FailedBatch<K>> failedBatches;

    public BatchExecutionResult(long totalCount, long successCount, long failCount, int batchCount, Duration duration) {
        this(totalCount, successCount, failCount, batchCount, duration, Collections.emptyList());
    }

    public BatchExecutionResult(long totalCount, long successCount, long failCount, int batchCount, Duration duration, List<FailedBatch<K>> failedBatches) {
        this.totalCount = totalCount;
        this.successCount = successCount;
        this.failCount = failCount;
        this.batchCount = batchCount;
        this.duration = duration != null ? duration : Duration.ZERO;
        this.failedBatches = (failedBatches != null && !failedBatches.isEmpty())
                ? Collections.unmodifiableList(new ArrayList<>(failedBatches))
                : Collections.emptyList();
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

    @Override
    public String toString() {
        return "BatchExecutionResult{" +
                "totalCount=" + totalCount +
                ", successCount=" + successCount +
                ", failCount=" + failCount +
                ", batchCount=" + batchCount +
                ", failedBatchCount=" + failedBatches.size() +
                ", duration=" + duration.toMillis() + "ms" +
                '}';
    }
}
