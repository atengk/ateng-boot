package io.github.atengk.boot.job.local.metrics;

import io.github.atengk.boot.job.local.enums.DiscardReason;
import io.github.atengk.boot.job.local.enums.TriggerType;
import io.github.atengk.boot.job.local.event.JobDiscardedEvent;
import io.github.atengk.boot.job.local.event.JobFailedEvent;
import io.github.atengk.boot.job.local.event.JobSuccessEvent;
import io.github.atengk.boot.job.local.event.JobTimeoutEvent;
import io.github.atengk.boot.job.local.registry.LocalJobRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.event.EventListener;

import java.util.concurrent.TimeUnit;

/**
 * 单机定时任务云原生 Micrometer 监控指标度量导出器
 * <p>
 * 监听任务生命周期与领域事件总线，将关键指标导出至 Prometheus / Grafana：
 * <ol>
 *   <li><b>执行延迟与状态度量 (Timer)</b>：{@code ateng.job.local.execution}，多维标签：{@code jobName}、{@code status} (SUCCESS/FAILED/TIMEOUT)、{@code triggerType}；</li>
 *   <li><b>丢弃与跳过度量 (Counter)</b>：{@code ateng.job.local.discarded}，多维标签：{@code jobName}、{@code triggerType}、{@code reason} (OVERLAPPING_DISCARD/CLUSTER_LOCKED)；</li>
 *   <li><b>活跃并发实例度量 (Gauge)</b>：{@code ateng.job.local.active}，实时透出当前系统活跃 Worker 线程执行中任务总数。</li>
 * </ol>
 * </p>
 *
 * @author Ateng
 * @since 2026-11-01
 */
public class LocalJobMetricsBinder implements MeterBinder {

    public static final String METRIC_EXECUTION = "ateng.job.local.execution";
    public static final String METRIC_DISCARDED = "ateng.job.local.discarded";
    public static final String METRIC_ACTIVE = "ateng.job.local.active";

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_TIMEOUT = "TIMEOUT";

    public static final String REASON_OVERLAPPING_DISCARD = "OVERLAPPING_DISCARD";
    public static final String REASON_CLUSTER_LOCKED = "CLUSTER_LOCKED";

    private final LocalJobRegistry localJobRegistry;
    private volatile MeterRegistry meterRegistry;

    public LocalJobMetricsBinder(LocalJobRegistry localJobRegistry) {
        this(localJobRegistry, null);
    }

    public LocalJobMetricsBinder(LocalJobRegistry localJobRegistry, MeterRegistry meterRegistry) {
        this.localJobRegistry = localJobRegistry;
        if (meterRegistry != null) {
            bindTo(meterRegistry);
        }
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        if (registry == null) {
            return;
        }
        this.meterRegistry = registry;

        if (localJobRegistry != null) {
            Gauge.builder(METRIC_ACTIVE, localJobRegistry, LocalJobRegistry::getActiveExecutionCount)
                    .description("Current active worker executions across local jobs")
                    .register(registry);
        }
    }

    /**
     * 监听任务执行成功事件并记录执行耗时
     *
     * @param event 成功事件
     */
    @EventListener
    public void onJobSuccess(JobSuccessEvent event) {
        if (event != null) {
            recordExecution(event.getJobName(), STATUS_SUCCESS, event.getTriggerType(), event.getDurationMs());
        }
    }

    /**
     * 监听任务执行失败事件并记录失败耗时与计数
     *
     * @param event 失败事件
     */
    @EventListener
    public void onJobFailed(JobFailedEvent event) {
        if (event != null) {
            recordExecution(event.getJobName(), STATUS_FAILED, event.getTriggerType(), event.getDurationMs());
        }
    }

    /**
     * 监听任务执行硬超时熔断事件并记录超时耗时与计数
     *
     * @param event 超时事件
     */
    @EventListener
    public void onJobTimeout(JobTimeoutEvent event) {
        if (event != null) {
            recordExecution(event.getJobName(), STATUS_TIMEOUT, event.getTriggerType(), event.getDurationMs());
        }
    }

    /**
     * 监听任务跳过/丢弃事件并记录跳过计数器
     *
     * @param event 丢弃事件
     */
    @EventListener
    public void onJobDiscarded(JobDiscardedEvent event) {
        if (meterRegistry == null || event == null) {
            return;
        }
        String jobName = event.getJobName() != null ? event.getJobName() : "UNKNOWN";
        String triggerType = event.getTriggerType() != null ? event.getTriggerType().name() : "UNKNOWN";
        String reason = event.getDiscardReason() != null
                ? event.getDiscardReason().name()
                : resolveDiscardReason(event.getReason());

        Counter.builder(METRIC_DISCARDED)
                .tag("jobName", jobName)
                .tag("triggerType", triggerType)
                .tag("reason", reason)
                .description("Count of discarded or skipped local job executions")
                .register(meterRegistry)
                .increment();
    }

    private void recordExecution(String jobName, String status, TriggerType triggerType, long durationMs) {
        if (meterRegistry == null) {
            return;
        }
        String triggerName = triggerType != null ? triggerType.name() : "UNKNOWN";
        Timer.builder(METRIC_EXECUTION)
                .tag("jobName", jobName != null ? jobName : "UNKNOWN")
                .tag("status", status)
                .tag("triggerType", triggerName)
                .description("Local job execution duration and counts")
                .register(meterRegistry)
                .record(Math.max(0, durationMs), TimeUnit.MILLISECONDS);
    }

    private String resolveDiscardReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "UNKNOWN";
        }
        String lower = reason.toLowerCase();
        if (lower.contains("cluster lock")) {
            return REASON_CLUSTER_LOCKED;
        }
        if (lower.contains("discard")) {
            return REASON_OVERLAPPING_DISCARD;
        }
        return reason.trim();
    }

    public MeterRegistry getMeterRegistry() {
        return meterRegistry;
    }
}
