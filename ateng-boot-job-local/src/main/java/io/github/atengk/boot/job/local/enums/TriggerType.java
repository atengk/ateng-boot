package io.github.atengk.boot.job.local.enums;

/**
 * 任务触发方式类型枚举
 *
 * @author Ateng
 * @since 2026-10-25
 */
public enum TriggerType {

    /**
     * Cron 表达式触发
     */
    CRON,

    /**
     * 固定频率触发（fixedRate）
     */
    FIXED_RATE,

    /**
     * 固定延迟触发（fixedDelay）
     */
    FIXED_DELAY,

    /**
     * 手动管理端点或 API 立即触发一次
     */
    MANUAL
}
