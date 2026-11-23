package io.github.atengk.boot.job.xxl.fallback;

import io.github.atengk.boot.job.xxl.watchdog.JobExecutionMetadata;

import java.time.Duration;

/**
 * XXL-Job 调度任务超时降级处理器 SPI 契约
 *
 * <p>当分布式受管任务被本地硬看门狗判定为超时并强行中断时，由框架受管异步线程池自动触发
 * 该降级回调组件，负责全局资源释放、外部锁解除、审计上报或状态回滚。</p>
 *
 * @author Ateng
 * @since 2026-11-02
 */
@FunctionalInterface
public interface JobTimeoutFallbackHandler {

    /**
     * 任务超时降级补偿处理回调
     *
     * @param metadata 任务执行元数据（包含 jobId, jobName, logId, traceId, tenantId）
     * @param elapsed  超时发生时的实际执行耗时
     */
    void onTimeout(JobExecutionMetadata metadata, Duration elapsed);

}
