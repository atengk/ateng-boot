package io.github.atengk.boot.liteflow.trace;

import lombok.Data;

import java.io.Serializable;

/**
 * LiteFlow 单节点步骤执行轨迹明细项
 * 记录单次规则执行中某个组件节点的 ID、Tag、耗时、状态与异常摘要
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
public class ExecutionStepItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标组件节点 ID
     */
    private String nodeId;

    /**
     * 节点标签 (Tag)
     */
    private String tag;

    /**
     * 单节点执行耗时（毫秒）
     */
    private long timeSpentMs;

    /**
     * 执行状态
     */
    private ExecutionStepStatus status = ExecutionStepStatus.SUCCESS;

    /**
     * 异常错误摘要（若节点执行失败）
     */
    private String errorSummary;

    /**
     * 执行所在线程名称（方便定位并行编排子线程）
     */
    private String threadName;

    /**
     * 步骤类型（如 SINGLE, START, END）
     */
    private String stepType;

    /**
     * 反向回滚耗时（毫秒，若发生 Saga 回滚）
     */
    private Long rollbackTimeSpentMs;

    public ExecutionStepItem() {
    }

    public ExecutionStepItem(String nodeId, String tag, long timeSpentMs, ExecutionStepStatus status) {
        this.nodeId = nodeId;
        this.tag = tag;
        this.timeSpentMs = timeSpentMs;
        this.status = status;
    }
}
