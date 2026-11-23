package io.github.atengk.boot.job.snail.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.Duration;

/**
 * SnailJob 任务本地硬超时中断领域事件
 *
 * <p>当任务执行时长超出看门狗设定的安全阈值被强制 {@link Thread#interrupt()} 时发布，
 * 供下游告警聚合器、运维度量组件或协同机器人监听消费。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Getter
public class SnailJobTimeoutEvent extends ApplicationEvent {

    private final String jobName;
    private final Long jobId;
    private final Long taskBatchId;
    private final Duration timeoutDuration;
    private final Duration elapsedDuration;
    private final String threadName;
    private final String traceId;

    public SnailJobTimeoutEvent(
            Object source,
            String jobName,
            Long jobId,
            Long taskBatchId,
            Duration timeoutDuration,
            Duration elapsedDuration,
            String threadName,
            String traceId) {
        super(source);
        this.jobName = jobName;
        this.jobId = jobId;
        this.taskBatchId = taskBatchId;
        this.timeoutDuration = timeoutDuration;
        this.elapsedDuration = elapsedDuration;
        this.threadName = threadName;
        this.traceId = traceId;
    }
}
