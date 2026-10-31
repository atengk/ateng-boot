package io.github.atengk.boot.job.xxl.watchdog;

/**
 * XXL-Job 调度任务执行元数据
 *
 * <p>封装单次任务调度的关键上下文标识，供本地看门狗与超时事件透传。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class JobExecutionMetadata {

    private final long jobId;
    private final String jobName;
    private final long logId;
    private final String traceId;
    private final String tenantId;

    public JobExecutionMetadata(long jobId, String jobName, long logId, String traceId, String tenantId) {
        this.jobId = jobId;
        this.jobName = jobName;
        this.logId = logId;
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

    public String getTraceId() {
        return traceId;
    }

    public String getTenantId() {
        return tenantId;
    }

}
