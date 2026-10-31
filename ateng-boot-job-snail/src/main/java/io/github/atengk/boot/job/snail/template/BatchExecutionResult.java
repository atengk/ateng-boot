package io.github.atengk.boot.job.snail.template;

import java.io.Serializable;
import java.time.Duration;

/**
 * 分布式批处理执行统计结果
 *
 * <p>封装分片批处理或多租户遍历执行的总处理条数、成功数、失败数、分批批次计数与整体执行耗时。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class BatchExecutionResult implements Serializable {

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

    public BatchExecutionResult(long totalCount, long successCount, long failCount, int batchCount, Duration duration) {
        this.totalCount = totalCount;
        this.successCount = successCount;
        this.failCount = failCount;
        this.batchCount = batchCount;
        this.duration = duration != null ? duration : Duration.ZERO;
    }

    /**
     * 是否全部记录处理成功（无任何失败记录）
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

    @Override
    public String toString() {
        return "BatchExecutionResult{" +
                "totalCount=" + totalCount +
                ", successCount=" + successCount +
                ", failCount=" + failCount +
                ", batchCount=" + batchCount +
                ", duration=" + duration.toMillis() + "ms" +
                '}';
    }
}
