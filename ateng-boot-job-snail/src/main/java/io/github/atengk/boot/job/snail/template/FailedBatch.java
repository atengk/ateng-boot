package io.github.atengk.boot.job.snail.template;

import java.io.Serializable;

/**
 * SnailJob 批处理失败分片/切片上下文模型
 *
 * <p>记录分批处理或分片消费失败时的起始游标、截止游标、分片索引及具体因果异常，
 * 消除流式批处理任务中的失败追踪黑洞，支持精准补偿与重试。</p>
 *
 * @param <K> 游标主键类型
 * @author Ateng
 * @since 2026-11-02
 */
public record FailedBatch<K>(
        K cursorStart,
        K cursorEnd,
        Integer shardIndex,
        int batchIndex,
        int itemCount,
        Throwable throwable
) implements Serializable {

    public FailedBatch(K cursorStart, K cursorEnd, Integer shardIndex, Throwable throwable) {
        this(cursorStart, cursorEnd, shardIndex, 0, 0, throwable);
    }

    public K getCursorStart() {
        return cursorStart;
    }

    public K getCursorEnd() {
        return cursorEnd;
    }

    public Integer getShardIndex() {
        return shardIndex;
    }

    public int getBatchIndex() {
        return batchIndex;
    }

    public int getItemCount() {
        return itemCount;
    }

    public Throwable getThrowable() {
        return throwable;
    }
}
