package io.github.atengk.boot.sample.job.local.interceptor;

import io.github.atengk.boot.job.local.interceptor.LocalJobInterceptor;
import io.github.atengk.boot.job.local.model.JobContext;
import io.github.atengk.boot.job.local.model.JobResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 系统发版维护窗口切面责任链拦截器
 * <p>
 * 演示 {@link LocalJobInterceptor} 三阶段切面拦截能力：开启维护窗口后前置拦截拒绝新调度执行，
 * 发布 {@code DISCARDED} 状态并触发逆序 {@code afterCompletion} 终态清理。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Component
public class SampleMaintenanceInterceptor implements LocalJobInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SampleMaintenanceInterceptor.class);

    private final AtomicBoolean maintenanceMode = new AtomicBoolean(false);
    private final AtomicInteger postHandleCount = new AtomicInteger(0);
    private final AtomicInteger afterCompletionCount = new AtomicInteger(0);

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public boolean preHandle(JobContext context) {
        if (maintenanceMode.get()) {
            log.warn("SampleMaintenanceInterceptor 检测到系统处于发版维护窗口，拦截跳过任务 [{}]",
                    context != null ? context.getJobName() : "UNKNOWN");
            return false;
        }
        return true;
    }

    @Override
    public void postHandle(JobContext context, JobResult result) {
        postHandleCount.incrementAndGet();
    }

    @Override
    public void afterCompletion(JobContext context, JobResult result, Throwable ex) {
        afterCompletionCount.incrementAndGet();
    }

    /**
     * 动态开关系统维护窗口拦截模式
     *
     * @param enabled true 开启维护拦截；false 恢复正常调度
     */
    public void setMaintenanceMode(boolean enabled) {
        this.maintenanceMode.set(enabled);
    }

    /**
     * 获取当前是否处于系统维护窗口拦截模式
     *
     * @return true 表示维护拦截中
     */
    public boolean isMaintenanceMode() {
        return maintenanceMode.get();
    }

    public int getPostHandleCount() {
        return postHandleCount.get();
    }

    public int getAfterCompletionCount() {
        return afterCompletionCount.get();
    }

    public void resetCounters() {
        postHandleCount.set(0);
        afterCompletionCount.set(0);
    }
}
