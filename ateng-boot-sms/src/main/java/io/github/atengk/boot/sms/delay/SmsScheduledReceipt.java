package io.github.atengk.boot.sms.delay;

import lombok.Getter;

import java.time.Instant;
import java.util.Objects;

/**
 * 延迟或定时短信调度任务回执句柄
 * 提供任务唯一标识、预定触发时刻、批量标记与运行期对称撤销能力
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Getter
public class SmsScheduledReceipt {

    /**
     * 调度任务全局唯一标识
     */
    private final String taskId;

    /**
     * 预定触发时刻
     */
    private final Instant triggerAt;

    /**
     * 目标渠道提示名称（可能为默认渠道或上下文动态渠道）
     */
    private final String channelName;

    /**
     * 是否为批量短信任务
     */
    private final boolean batch;

    /**
     * 目标手机号数量
     */
    private final int phoneCount;

    private final transient SmsDelayScheduler scheduler;

    public SmsScheduledReceipt(String taskId,
                               Instant triggerAt,
                               String channelName,
                               boolean batch,
                               int phoneCount,
                               SmsDelayScheduler scheduler) {
        this.taskId = Objects.requireNonNull(taskId, "taskId 不能为空");
        this.triggerAt = Objects.requireNonNull(triggerAt, "triggerAt 不能为空");
        this.channelName = channelName;
        this.batch = batch;
        this.phoneCount = phoneCount;
        this.scheduler = scheduler;
    }

    /**
     * 尝试撤销取消尚未触发的延迟发信任务
     *
     * @return true 表示成功取消，false 表示任务已触发、已完成或先前已被取消
     */
    public boolean cancel() {
        return scheduler != null && scheduler.cancel(taskId);
    }

    /**
     * 判断当前调度任务是否处于已取消状态
     *
     * @return true 表示已取消
     */
    public boolean isCancelled() {
        return scheduler != null && scheduler.isCancelled(taskId);
    }

    /**
     * 判断当前调度任务是否已经终结（包含完成、取消或执行失败）
     *
     * @return true 表示已终结
     */
    public boolean isDone() {
        return scheduler != null && scheduler.isDone(taskId);
    }

    /**
     * 查询当前调度任务状态
     *
     * @return SmsScheduledStatus，若任务状态已过期回收则返回 null
     */
    public SmsScheduledStatus getStatus() {
        return scheduler != null ? scheduler.getStatus(taskId) : null;
    }
}
