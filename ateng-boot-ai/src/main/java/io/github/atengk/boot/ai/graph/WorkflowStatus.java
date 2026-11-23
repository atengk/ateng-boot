package io.github.atengk.boot.ai.graph;

/**
 * 认知状态图生命周期状态机枚举
 *
 * @author Ateng
 * @since 2026-11-03
 */
public enum WorkflowStatus {

    /**
     * 初始执行中 / 正在运行
     */
    RUNNING,

    /**
     * 断点挂起中（遇到人工审查或审批中断节点，释放工作线程等待外部干预）
     */
    SUSPENDED,

    /**
     * 终态：执行成功并完成流转
     */
    COMPLETED,

    /**
     * 终态：执行发生不可自愈异常导致失败
     */
    FAILED,

    /**
     * 终态：被人工或系统显式主动废弃与撤销
     */
    CANCELLED;

    /**
     * 判断当前状态是否为不可变终态
     *
     * @return 若已完成、已失败或已取消则返回 true
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /**
     * 判断当前状态是否允许恢复执行
     *
     * @return 仅当处于 SUSPENDED 挂起状态时允许恢复
     */
    public boolean canResume() {
        return this == SUSPENDED;
    }
}
