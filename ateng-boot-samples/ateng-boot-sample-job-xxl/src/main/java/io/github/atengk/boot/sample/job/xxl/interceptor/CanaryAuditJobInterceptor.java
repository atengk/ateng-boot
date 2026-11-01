package io.github.atengk.boot.sample.job.xxl.interceptor;

import io.github.atengk.boot.job.xxl.interceptor.XxlJobExecutionContext;
import io.github.atengk.boot.job.xxl.interceptor.XxlJobInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 灰度拦截与审计日志切面拦截器样例
 *
 * <p>展示 XXL-Job 执行器拦截器 SPI 的两类核心场景：
 * 1. 灰度拦截：对特定任务判定灰度规则，返回 {@code false} 优雅跳过执行并向调度中心上报 200 SUCCESS 成功；
 * 2. 审计度量：对正常执行任务全生命周期记录审计追踪与执行计数。</p>
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Component
public class CanaryAuditJobInterceptor implements XxlJobInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CanaryAuditJobInterceptor.class);

    private final List<String> auditedJobs = new CopyOnWriteArrayList<>();
    private final AtomicInteger skippedCount = new AtomicInteger();
    private final AtomicInteger completedCount = new AtomicInteger();

    @Override
    public boolean preHandle(XxlJobExecutionContext context) throws Exception {
        String jobName = context.getJobName();

        // 灰度拦截跳过规则：若任务名为 canarySkipJob，则优雅拦截并直接报告 200 SUCCESS
        if ("canarySkipJob".equalsIgnoreCase(jobName)) {
            log.warn(">>>>>> [CanaryAuditJobInterceptor] 任务 [{}] 命中灰度拦截跳过规则，优雅终止执行！", jobName);
            skippedCount.incrementAndGet();
            return false;
        }

        log.info(">>>>>> [CanaryAuditJobInterceptor] [preHandle] 任务 [{}] 准入审计放行, traceId: {}",
                jobName, context.getTraceId());
        auditedJobs.add(jobName);
        return true;
    }

    @Override
    public void afterCompletion(XxlJobExecutionContext context, Throwable throwable) throws Exception {
        completedCount.incrementAndGet();
        log.info(">>>>>> [CanaryAuditJobInterceptor] [afterCompletion] 任务 [{}] 执行终态归档, 异常: {}",
                context.getJobName(), throwable != null ? throwable.getMessage() : "none");
    }

    public List<String> getAuditedJobs() {
        return auditedJobs;
    }

    public int getSkippedCount() {
        return skippedCount.get();
    }

    public int getCompletedCount() {
        return completedCount.get();
    }

    public void clear() {
        auditedJobs.clear();
        skippedCount.set(0);
        completedCount.set(0);
    }
}
