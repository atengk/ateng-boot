package io.github.atengk.boot.job.snail.watchdog;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SnailJob 任务执行上下文元数据
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SnailJobExecutionMetadata {

    /**
     * 调度任务标识名称
     */
    private String jobName;

    /**
     * SnailJob 调度任务 ID
     */
    private Long jobId;

    /**
     * 调度批次 ID
     */
    private Long taskBatchId;

    /**
     * 工作流任务批次 ID
     */
    private Long workflowTaskBatchId;

    /**
     * 工作流节点 ID
     */
    private Long workflowNodeId;

    /**
     * 任务业务唯一标识
     */
    private String jobBizId;

    /**
     * 全链路追踪 traceId
     */
    private String traceId;

    /**
     * 多租户标识
     */
    private String tenantId;
}
