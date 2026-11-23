package io.github.atengk.boot.sample.job.xxl.interceptor;

import io.github.atengk.boot.job.xxl.interceptor.XxlJobExecutionContext;
import io.github.atengk.boot.job.xxl.interceptor.XxlJobInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 灰度拦截与审计日志切面拦截器样例
 *
 * <p>展示 XXL-Job 执行器拦截器 SPI 的两类核心场景：
 * 1. 灰度拦截：对特定任务判定灰度规则，返回 {@code false} 优雅跳过执行并向调度中心上报 200 SUCCESS 成功；
 * 2. 审计度量：对正常执行任务全生命周期记录 {@link XxlJobExecutionContext} 元数据（{@code jobId}、{@code logId}、分片与追踪）与执行计数。</p>
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Component
public class CanaryAuditJobInterceptor implements XxlJobInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CanaryAuditJobInterceptor.class);

    private final List<String> auditedJobs = new CopyOnWriteArrayList<>();
    private final AtomicInteger skippedCount = new AtomicInteger();
    private final AtomicInteger postHandledCount = new AtomicInteger();
    private final AtomicInteger completedCount = new AtomicInteger();
    private final AtomicReference<AuditMetadata> lastCapturedMeta = new AtomicReference<>();

    @Override
    public boolean preHandle(XxlJobExecutionContext context) throws Exception {
        String jobName = context.getJobName();
        AuditMetadata meta = new AuditMetadata(
                context.getJobId(),
                context.getLogId(),
                context.getJobHandler(),
                context.getShardIndex(),
                context.getShardTotal(),
                context.getTraceId()
        );
        lastCapturedMeta.set(meta);

        // 灰度拦截跳过规则：若任务名为 canarySkipJob，则优雅拦截并直接报告 200 SUCCESS
        if ("canarySkipJob".equalsIgnoreCase(jobName)) {
            log.warn(">>>>>> [CanaryAuditJobInterceptor] 任务 [{}] (jobId={}, logId={}) 命中灰度拦截跳过规则，优雅终止执行！",
                    jobName, meta.jobId(), meta.logId());
            skippedCount.incrementAndGet();
            return false;
        }

        log.info(">>>>>> [CanaryAuditJobInterceptor] [preHandle] 任务 [{}] 准入审计放行, jobId={}, logId={}, shard={}/{}, traceId={}",
                jobName, meta.jobId(), meta.logId(), meta.shardIndex(), meta.shardTotal(), meta.traceId());
        auditedJobs.add(jobName);
        return true;
    }

    @Override
    public void postHandle(XxlJobExecutionContext context, Object result) throws Exception {
        postHandledCount.incrementAndGet();
        log.info(">>>>>> [CanaryAuditJobInterceptor] [postHandle] 任务 [{}] (jobId={}, logId={}) 正常完成回调",
                context.getJobHandler(), context.getJobId(), context.getLogId());
    }

    @Override
    public void afterCompletion(XxlJobExecutionContext context, Throwable throwable) throws Exception {
        completedCount.incrementAndGet();
        log.info(">>>>>> [CanaryAuditJobInterceptor] [afterCompletion] 任务 [{}] (jobId={}, logId={}) 执行终态归档, 异常: {}",
                context.getJobHandler(), context.getJobId(), context.getLogId(),
                throwable != null ? throwable.getMessage() : "none");
    }

    public List<String> getAuditedJobs() {
        return auditedJobs;
    }

    public int getSkippedCount() {
        return skippedCount.get();
    }

    public int getPostHandledCount() {
        return postHandledCount.get();
    }

    public int getCompletedCount() {
        return completedCount.get();
    }

    public AuditMetadata getLastCapturedMeta() {
        return lastCapturedMeta.get();
    }

    public void clear() {
        auditedJobs.clear();
        skippedCount.set(0);
        postHandledCount.set(0);
        completedCount.set(0);
        lastCapturedMeta.set(null);
    }

    /**
     * 拦截器捕获的任务执行元数据快照
     */
    public record AuditMetadata(
            long jobId,
            long logId,
            String jobHandler,
            int shardIndex,
            int shardTotal,
            String traceId
    ) {
    }
}
