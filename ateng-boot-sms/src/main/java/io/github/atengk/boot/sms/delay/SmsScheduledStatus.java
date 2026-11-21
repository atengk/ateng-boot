package io.github.atengk.boot.sms.delay;

/**
 * 延迟或定时短信调度任务生命周期状态枚举
 *
 * @author Ateng
 * @since 2026-11-21
 */
public enum SmsScheduledStatus {

    /**
     * 已入队待触发
     */
    PENDING,

    /**
     * 已到点触发，正在执行发信
     */
    RUNNING,

    /**
     * 已触发并完成执行（含被实时风控静默拦截的正常完结态）
     */
    COMPLETED,

    /**
     * 触发前已被主动撤销取消
     */
    CANCELLED,

    /**
     * 到点执行期遭遇异常失败
     */
    FAILED
}
