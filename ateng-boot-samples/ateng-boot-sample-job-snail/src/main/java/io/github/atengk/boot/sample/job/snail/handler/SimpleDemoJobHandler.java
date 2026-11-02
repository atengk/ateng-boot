package io.github.atengk.boot.sample.job.snail.handler;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.job.snail.annotation.AtengSnailJob;
import io.github.atengk.boot.job.snail.context.SnailJobContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 基础调度任务执行器示例
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Slf4j
@Component
public class SimpleDemoJobHandler {

    /**
     * 极简调度任务：问候任务
     */
    @JobExecutor(name = "simpleGreetingJob")
    public ExecuteResult simpleGreetingJob() {
        String traceId = SnailJobContextHolder.getTraceId();
        log.info("[SimpleDemoJobHandler] 执行极简问候任务, traceId: {}", traceId);
        return ExecuteResult.success("Greeting executed with traceId: " + traceId);
    }

    /**
     * 超时守卫增强调度任务
     */
    @AtengSnailJob(timeout = "500ms")
    @JobExecutor(name = "timeoutGuardedJob")
    public ExecuteResult timeoutGuardedJob() {
        log.info("[SimpleDemoJobHandler] 执行超时守卫增强任务, 当前租户: {}", SnailJobContextHolder.getTenantId());
        return ExecuteResult.success("Timeout guarded job succeeded");
    }

    /**
     * 金丝雀灰度绕过演示任务
     */
    @JobExecutor(name = "canaryBypassJob")
    public ExecuteResult canaryBypassJob() {
        log.info("[SimpleDemoJobHandler] 执行金丝雀任务，正常情况下应被拦截器跳过");
        return ExecuteResult.success("Canary executed");
    }
}
