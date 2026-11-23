package io.github.atengk.boot.sample.job.snail.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 流式分片批处理失败死信切片视图模型
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeadLetterBatchVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 批次起始游标
     */
    private Long cursorStart;

    /**
     * 批次结束游标
     */
    private Long cursorEnd;

    /**
     * 分片索引编号
     */
    private Integer shardIndex;

    /**
     * 批次数据条数
     */
    private Integer itemCount;

    /**
     * 失败异常原因说明
     */
    private String failureReason;
}
