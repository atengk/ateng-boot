package io.github.atengk.boot.job.snail.metrics;

import io.github.atengk.boot.job.snail.drain.GracefulDrainStateMachine;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * SnailJob 任务调度与分布式重试双擎 Micrometer 核心监控度量绑定器
 *
 * <p>遵循云原生 Observability 规范，统一收集并发布调度任务与重试执行度量指标：
 * <ul>
 *     <li><b>调度指标名称</b>：{@code ateng.job.snail.job.execution}；</li>
 *     <li><b>重试指标名称</b>：{@code ateng.job.snail.retry.execution}；</li>
 *     <li><b>核心维度 (Tags)</b>：{@code job}/{@code scene}、{@code status} (SUCCESS / FAILURE)、{@code tenant} (租户标识)；</li>
 *     <li><b>度量数据</b>：任务执行总吞吐量 (Throughput Count)、失败计数 (Failures)、耗时百分位数分布 (P50/P95/P99 Latency)。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class SnailJobMetricsBinder implements MeterBinder {

    public static final String JOB_METRIC_NAME = "ateng.job.snail.job.execution";
    public static final String JOB_METRIC_DESCRIPTION = "SnailJob 调度任务执行吞吐量与耗时百分位度量";

    public static final String RETRY_METRIC_NAME = "ateng.job.snail.retry.execution";
    public static final String RETRY_METRIC_DESCRIPTION = "SnailJob 分布式重试执行吞吐量与耗时百分位度量";

    public static final String INFLIGHT_JOB_METRIC_NAME = "ateng.job.snail.job.inflight";
    public static final String INFLIGHT_JOB_METRIC_DESCRIPTION = "SnailJob 当前在途执行中的调度任务并发数";

    public static final String INFLIGHT_RETRY_METRIC_NAME = "ateng.job.snail.retry.inflight";
    public static final String INFLIGHT_RETRY_METRIC_DESCRIPTION = "SnailJob 当前在途执行中的重试任务并发数";

    public static final String TAG_JOB = "job";
    public static final String TAG_SCENE = "scene";
    public static final String TAG_STATUS = "status";
    public static final String TAG_TENANT = "tenant";

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILURE = "FAILURE";

    private final GracefulDrainStateMachine drainStateMachine;
    private MeterRegistry registry;
    private final ConcurrentMap<String, Timer> timerCache = new ConcurrentHashMap<>();

    public SnailJobMetricsBinder() {
        this(null);
    }

    public SnailJobMetricsBinder(GracefulDrainStateMachine drainStateMachine) {
        this.drainStateMachine = drainStateMachine;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        this.registry = registry;
        if (drainStateMachine != null) {
            Gauge.builder(INFLIGHT_JOB_METRIC_NAME, drainStateMachine, GracefulDrainStateMachine::getInFlightJobCount)
                    .description(INFLIGHT_JOB_METRIC_DESCRIPTION)
                    .register(registry);
            Gauge.builder(INFLIGHT_RETRY_METRIC_NAME, drainStateMachine, GracefulDrainStateMachine::getInFlightRetryCount)
                    .description(INFLIGHT_RETRY_METRIC_DESCRIPTION)
                    .register(registry);
        }
    }

    /**
     * 记录一次调度任务的执行度量
     *
     * @param jobName  任务名称或 Handler 标识
     * @param status   执行状态 (SUCCESS / FAILURE)
     * @param tenantId 租户标识 (可为 null)
     * @param duration 执行耗时
     */
    public void recordJobExecution(String jobName, String status, String tenantId, Duration duration) {
        if (registry == null || duration == null) {
            return;
        }

        String safeJob = StringUtils.hasText(jobName) ? jobName : "unknownJob";
        String safeStatus = StringUtils.hasText(status) ? status : STATUS_SUCCESS;
        String safeTenant = StringUtils.hasText(tenantId) ? tenantId : "default";

        String key = "job#" + safeJob + "#" + safeStatus + "#" + safeTenant;
        Timer timer = timerCache.computeIfAbsent(key, k ->
                Timer.builder(JOB_METRIC_NAME)
                        .description(JOB_METRIC_DESCRIPTION)
                        .tags(Tags.of(TAG_JOB, safeJob, TAG_STATUS, safeStatus, TAG_TENANT, safeTenant))
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(registry)
        );

        timer.record(duration);
    }

    /**
     * 记录一次重试场景的执行度量
     *
     * @param scene    重试场景名称
     * @param status   执行状态 (SUCCESS / FAILURE)
     * @param tenantId 租户标识 (可为 null)
     * @param duration 执行耗时
     */
    public void recordRetryExecution(String scene, String status, String tenantId, Duration duration) {
        if (registry == null || duration == null) {
            return;
        }

        String safeScene = StringUtils.hasText(scene) ? scene : "unknownScene";
        String safeStatus = StringUtils.hasText(status) ? status : STATUS_SUCCESS;
        String safeTenant = StringUtils.hasText(tenantId) ? tenantId : "default";

        String key = "retry#" + safeScene + "#" + safeStatus + "#" + safeTenant;
        Timer timer = timerCache.computeIfAbsent(key, k ->
                Timer.builder(RETRY_METRIC_NAME)
                        .description(RETRY_METRIC_DESCRIPTION)
                        .tags(Tags.of(TAG_SCENE, safeScene, TAG_STATUS, safeStatus, TAG_TENANT, safeTenant))
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(registry)
        );

        timer.record(duration);
    }

    /**
     * 获取当前绑定的 MeterRegistry
     *
     * @return 注册中心实例
     */
    public MeterRegistry getRegistry() {
        return registry;
    }
}
