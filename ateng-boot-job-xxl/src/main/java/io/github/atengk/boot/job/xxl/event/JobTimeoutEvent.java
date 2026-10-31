package io.github.atengk.boot.job.xxl.event;

import org.springframework.context.ApplicationEvent;

import java.time.Duration;

/**
 * XXL-Job 任务执行本地硬超时事件
 *
 * <p>当调度任务在执行器端超过声明的超时阈值被本地看门狗强行中断时，由 Spring 事件机制广播发布，
 * 供下游告警中心、审计监控与韧性治理组件监听并处理。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class JobTimeoutEvent extends ApplicationEvent {

    private final long jobId;
    private final String jobName;
    private final long logId;
    private final Duration timeout;
    private final Duration executionDuration;
    private final String threadName;
    private final String traceId;
    private final String tenantId;

    public JobTimeoutEvent(Object source,
                           long jobId,
                           String jobName,
                           long logId,
                           Duration timeout,
                           Duration executionDuration,
                           String threadName,
                           String traceId,
                           String tenantId) {
        super(source);
        this.jobId = jobId;
        this.jobName = jobName;
        this.logId = logId;
        this.timeout = timeout;
        this.executionDuration = executionDuration;
        this.threadName = threadName;
        this.traceId = traceId;
        this.tenantId = tenantId;
    }

    public long getJobId() {
        return jobId;
    }

    public String getJobName() {
        return jobName;
    }

    public long getLogId() {
        return logId;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public Duration getExecutionDuration() {
        return executionDuration;
    }

    public String getThreadName() {
        return threadName;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getTenantId() {
        return tenantId;
    }

    @Override
    public String toString() {
        return "JobTimeoutEvent{" +
                "jobId=" + jobId +
                ", jobName='" + jobName + '\'' +
                ", logId=" + logId +
                ", timeout=" + timeout +
                ", executionDuration=" + executionDuration +
                ", threadName='" + threadName + '\'' +
                ", traceId='" + traceId + '\'' +
                ", tenantId='" + tenantId + '\'' +
                '}';
    }

}
