package io.github.atengk.boot.job.xxl.template;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 分片流式批处理失败切片封装模型
 *
 * <p>封装单批次消费失败时的记录明细列表、批次序号、游标 ID 与底层引发的异常原因，
 * 供失败补偿回调（failureHandler / 死信队列）执行定制化持久化或告警补偿。</p>
 *
 * @param <T> 实体类型
 * @author Ateng
 * @since 2026-11-01
 */
public class FailedBatch<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 本批次失败的记录明细列表
     */
    private final List<T> items;

    /**
     * 批次序号（从 1 开始）
     */
    private final int batchIndex;

    /**
     * 当前切片查询推进的游标 ID
     */
    private final Object cursorId;

    /**
     * 触发失败的底层异常原因
     */
    private final Throwable cause;

    public FailedBatch(List<T> items, int batchIndex, Object cursorId, Throwable cause) {
        this.items = (items != null && !items.isEmpty())
                ? Collections.unmodifiableList(new ArrayList<>(items))
                : Collections.emptyList();
        this.batchIndex = batchIndex;
        this.cursorId = cursorId;
        this.cause = cause;
    }

    /**
     * 获取本批次失败的记录明细列表
     *
     * @return 失败记录不可变列表
     */
    public List<T> getItems() {
        return items;
    }

    /**
     * 获取批次序号（从 1 开始）
     *
     * @return 批次序号
     */
    public int getBatchIndex() {
        return batchIndex;
    }

    /**
     * 获取批次编号（同 {@link #getBatchIndex()}）
     *
     * @return 批次编号
     */
    public int getBatchNumber() {
        return batchIndex;
    }

    /**
     * 获取当前切片推进的游标 ID
     *
     * @return 游标 ID
     */
    public Object getCursorId() {
        return cursorId;
    }

    /**
     * 获取底层异常原因
     *
     * @return 异常对象
     */
    public Throwable getCause() {
        return cause;
    }

    @Override
    public String toString() {
        return "FailedBatch{" +
                "itemsCount=" + items.size() +
                ", batchIndex=" + batchIndex +
                ", cursorId=" + cursorId +
                ", cause=" + (cause != null ? cause.getMessage() : "null") +
                '}';
    }
}
