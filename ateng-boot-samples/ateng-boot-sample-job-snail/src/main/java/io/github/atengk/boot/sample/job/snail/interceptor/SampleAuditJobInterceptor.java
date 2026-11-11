package io.github.atengk.boot.sample.job.snail.interceptor;

import io.github.atengk.boot.job.snail.interceptor.SnailJobExecutionContext;
import io.github.atengk.boot.job.snail.interceptor.SnailJobInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 样例调度生命周期审计与金丝雀灰度拦截器
 *
 * <p>演示 {@link SnailJobInterceptor} 三阶生命周期拦截能力：
 * <ul>
 *     <li>{@code preHandle}：执行前置审计记录、阶段共享属性注入与金丝雀灰度条件判定 (返回 {@code false} 优雅跳过)；</li>
 *     <li>{@code postHandle}：任务成功执行后记录后置审计日志；</li>
 *     <li>{@code afterCompletion}：无论成功或异常，在 {@code finally} 块中精准完成耗时统计与上下文清理。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
@Component
@Order(10)
public class SampleAuditJobInterceptor implements SnailJobInterceptor {

    private final AtomicInteger preHandleCount = new AtomicInteger(0);
    private final AtomicInteger postHandleCount = new AtomicInteger(0);
    private final AtomicInteger afterCompletionCount = new AtomicInteger(0);
    private final AtomicInteger canaryBypassCount = new AtomicInteger(0);
    private final Map<String, Long> executionDurationMap = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(SnailJobExecutionContext context) {
        preHandleCount.incrementAndGet();
        String jobName = context.getMetadata() != null ? context.getMetadata().getJobName() : "";
        Object[] args = context.getArgs();

        // 1. 金丝雀灰度特征检查：若任务名称为 vipChannelProbeJob 或参数携带 canary-bypass，触发优雅跳过
        boolean isCanaryBypass = "vipChannelProbeJob".equals(jobName) || "canaryBypassJob".equals(jobName);
        if (!isCanaryBypass && args != null) {
            for (Object arg : args) {
                if (arg instanceof String s && s.contains("canary-bypass")) {
                    isCanaryBypass = true;
                    break;
                }
            }
        }

        if (isCanaryBypass) {
            canaryBypassCount.incrementAndGet();
            log.info("[SampleAuditJobInterceptor] 命中金丝雀灰度绕过策略，优雅跳过任务执行: jobName={}", jobName);
            return false;
        }

        // 2. 正常任务记录前置审计追踪并挂载阶段起始时间
        context.setAttribute("auditStartTimeNanos", System.nanoTime());
        log.info("[SampleAuditJobInterceptor] 调度前置审计放行: jobName={}, method={}",
                jobName, context.getMethod() != null ? context.getMethod().getName() : "unknown");
        return true;
    }

    @Override
    public void postHandle(SnailJobExecutionContext context, Object result) {
        postHandleCount.incrementAndGet();
        String jobName = context.getMetadata() != null ? context.getMetadata().getJobName() : "";
        log.info("[SampleAuditJobInterceptor] 调度后置审计完成: jobName={}, result={}", jobName, result);
    }

    @Override
    public void afterCompletion(SnailJobExecutionContext context, Object result, Throwable ex) {
        afterCompletionCount.incrementAndGet();
        String jobName = context.getMetadata() != null ? context.getMetadata().getJobName() : "";
        Long startNanos = context.getAttribute("auditStartTimeNanos", Long.class);
        long elapsedNanos = startNanos != null ? (System.nanoTime() - startNanos) : 0L;
        executionDurationMap.put(jobName, elapsedNanos);

        log.info("[SampleAuditJobInterceptor] 调度终态审计闭环: jobName={}, elapsedNs={}, error={}",
                jobName, elapsedNanos, ex != null ? ex.getMessage() : "none");
    }

    public int getPreHandleCount() {
        return preHandleCount.get();
    }

    public int getPostHandleCount() {
        return postHandleCount.get();
    }

    public int getAfterCompletionCount() {
        return afterCompletionCount.get();
    }

    public int getCanaryBypassCount() {
        return canaryBypassCount.get();
    }

    public Long getExecutionDuration(String jobName) {
        return executionDurationMap.get(jobName);
    }

    public void reset() {
        preHandleCount.set(0);
        postHandleCount.set(0);
        afterCompletionCount.set(0);
        canaryBypassCount.set(0);
        executionDurationMap.clear();
    }
}
