package io.github.atengk.boot.sample.job.snail.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 分布式协同重试执行结果视图模型
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetryExecutionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 业务单据标识
     */
    private String orderId;

    /**
     * 重试场景标识
     */
    private String scene;

    /**
     * 幂等键标识
     */
    private String idempotentId;

    /**
     * 当前尝试次数
     */
    private Integer attemptCount;

    /**
     * 执行模式 (DECLARATIVE / PROGRAMMATIC / MANUAL_REPORT)
     */
    private String mode;

    /**
     * 执行状态
     */
    private String status;

    /**
     * 执行结果消息
     */
    private String message;
}
