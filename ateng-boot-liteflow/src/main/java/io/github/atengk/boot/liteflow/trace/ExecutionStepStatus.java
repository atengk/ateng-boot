package io.github.atengk.boot.liteflow.trace;

/**
 * LiteFlow 组件节点步骤执行状态枚举
 *
 * @author Ateng
 * @since 2026-11-03
 */
public enum ExecutionStepStatus {

    /**
     * 执行成功
     */
    SUCCESS,

    /**
     * 执行失败
     */
    FAIL,

    /**
     * 反向补偿回滚
     */
    ROLLBACK,

    /**
     * 条件跳过/未执行
     */
    SKIPPED
}
