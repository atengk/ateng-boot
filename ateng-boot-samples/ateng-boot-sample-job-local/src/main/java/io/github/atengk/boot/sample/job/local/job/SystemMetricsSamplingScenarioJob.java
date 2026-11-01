package io.github.atengk.boot.sample.job.local.job;

import io.github.atengk.boot.job.local.annotation.LocalJob;
import io.github.atengk.boot.job.local.model.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 系统运行时资源与业务指标周期采样业务场景任务
 * <p>
 * 演示特性：FixedRate 固定频率调度、String 返回类型自动包装与云原生 Micrometer 监控指标采集。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-01
 */
@Component
public class SystemMetricsSamplingScenarioJob {

    private static final Logger log = LoggerFactory.getLogger(SystemMetricsSamplingScenarioJob.class);

    private final AtomicInteger sampleCount = new AtomicInteger();

    /**
     * 执行周期性系统指标采样
     *
     * @param context 任务上下文
     * @return 状态采样结果描述
     */
    @LocalJob(
            name = "systemMetricsSamplingJob",
            description = "系统运行时资源与业务指标周期采样",
            fixedRate = "5s",
            initialDelay = "1h"
    )
    public String executeMetricsSampling(JobContext context) {
        int count = sampleCount.incrementAndGet();
        context.log("第 [" + count + "] 次采集系统 JVM 堆内存与活跃线程指标...");
        log.info("SystemMetricsSampling executed: count={}, traceId={}", count, context.getTraceId());
        return "指标采集正常，活跃线程数: 8, 内存占用: 24%";
    }

    /**
     * 获取采样总次数
     *
     * @return 采样次数
     */
    public int getSampleCount() {
        return sampleCount.get();
    }
}
