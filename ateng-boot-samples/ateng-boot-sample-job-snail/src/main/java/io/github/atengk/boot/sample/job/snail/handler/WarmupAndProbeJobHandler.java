package io.github.atengk.boot.sample.job.snail.handler;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.job.snail.annotation.AtengSnailJob;
import io.github.atengk.boot.job.snail.context.SnailJobContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 系统预热与金丝雀灰度健康探测任务执行器
 *
 * <p>面向微服务冷启动热点缓存预热、基础设施健康巡检与金丝雀灰度拦截验证。</p>
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Component
public class WarmupAndProbeJobHandler {

    /**
     * 系统冷启动热点缓存预热与健康巡检任务
     */
    @JobExecutor(name = "systemWarmupJob")
    public ExecuteResult systemWarmupJob() {
        String traceId = SnailJobContextHolder.getTraceId();
        log.info("[WarmupAndProbeJobHandler] 执行系统热点缓存预热与健康巡检任务, traceId: {}", traceId);
        return ExecuteResult.success("System warmup and health check completed with traceId: " + traceId);
    }

    /**
     * VIP 专属交易专线健康探测任务 (演示拦截器金丝雀灰度判定跳过)
     */
    @JobExecutor(name = "vipChannelProbeJob")
    public ExecuteResult vipChannelProbeJob() {
        log.info("[WarmupAndProbeJobHandler] 执行 VIP 专线金丝雀探测任务，正常情况下应被拦截器优雅跳过");
        return ExecuteResult.success("VIP channel probe executed");
    }

    /**
     * 超时守卫增强调度任务 (声明式本地看门狗超时监控)
     */
    @AtengSnailJob(timeout = "500ms")
    @JobExecutor(name = "timeoutGuardedJob")
    public ExecuteResult timeoutGuardedJob() {
        log.info("[WarmupAndProbeJobHandler] 执行超时守卫增强任务, 当前租户: {}", SnailJobContextHolder.getTenantId());
        return ExecuteResult.success("Timeout guarded job succeeded");
    }
}
