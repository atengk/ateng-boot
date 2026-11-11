package io.github.atengk.boot.sample.job.xxl.vo;

import io.github.atengk.boot.job.xxl.template.FailedBatch;
import io.github.atengk.boot.sample.job.xxl.handler.ShardingBatchJobHandler.MockOrder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 分片流式批处理失败切片死信视图对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeadLetterBatchVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 批次序号（从 1 开始）
     */
    private int batchIndex;

    /**
     * 失败明细条数
     */
    private int itemCount;

    /**
     * 当前切片推进的游标 ID
     */
    private Object cursorId;

    /**
     * 触发失败的底层异常原因摘要
     */
    private String errorMessage;

    /**
     * 失败订单记录明细
     */
    private List<MockOrder> items;

    /**
     * 从内核 FailedBatch 模型转换为视图对象
     *
     * @param failedBatch 失败切片对象
     * @return 转换后的 VO
     */
    public static DeadLetterBatchVO from(FailedBatch<MockOrder> failedBatch) {
        if (failedBatch == null) {
            return null;
        }
        return DeadLetterBatchVO.builder()
                .batchIndex(failedBatch.getBatchIndex())
                .itemCount(failedBatch.getItems() != null ? failedBatch.getItems().size() : 0)
                .cursorId(failedBatch.getCursorId())
                .errorMessage(failedBatch.getCause() != null ? failedBatch.getCause().getMessage() : null)
                .items(failedBatch.getItems())
                .build();
    }
}
