package io.github.atengk.boot.job.local.enums;

/**
 * 任务生命周期运行状态枚举
 *
 * @author Ateng
 * @since 2026-10-25
 */
public enum JobStatus {

    /**
     * 正常运行中（处于调度周期中）
     */
    RUNNING,

    /**
     * 已暂停（停止周期触发，但未注销）
     */
    PAUSED,

    /**
     * 已停止/已注销
     */
    STOPPED
}
