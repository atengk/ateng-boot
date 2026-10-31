package io.github.atengk.boot.job.xxl.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * XXL-Job 调度任务 Micrometer 核心监控度量绑定器
 *
 * <p>遵循云原生 Observability 规范，统一收集并发布任务执行度量指标：
 * <ul>
 *     <li><b>指标名称</b>：{@code ateng.job.xxl.execution}；</li>
 *     <li><b>核心维度 (Tags)</b>：{@code handler} (任务处理器名称)、{@code status} (SUCCESS / FAILURE)、{@code tenant} (租户标识)；</li>
 *     <li><b>度量数据</b>：任务执行总吞吐量 (Throughput Count)、失败计数 (Failures)、耗时百分位数分布 (P50/P95/P99 Latency)。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class XxlJobMetricsBinder implements MeterBinder {

    public static final String METRIC_NAME = "ateng.job.xxl.execution";
    public static final String METRIC_DESCRIPTION = "XXL-Job 任务执行吞吐量与耗时百分位度量";

    public static final String TAG_HANDLER = "handler";
    public static final String TAG_STATUS = "status";
    public static final String TAG_TENANT = "tenant";

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILURE = "FAILURE";

    private MeterRegistry registry;
    private final ConcurrentMap<String, Timer> timerCache = new ConcurrentHashMap<>();

    @Override
    public void bindTo(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 记录一次调度任务的执行度量
     *
     * @param jobHandler 任务 Handler 名称
     * @param status     执行状态 (SUCCESS / FAILURE)
     * @param tenantId   租户标识 (可为 null)
     * @param duration   执行耗时
     */
    public void recordExecution(String jobHandler, String status, String tenantId, Duration duration) {
        if (registry == null || duration == null) {
            return;
        }

        String safeHandler = StringUtils.hasText(jobHandler) ? jobHandler : "unknownHandler";
        String safeStatus = StringUtils.hasText(status) ? status : STATUS_SUCCESS;
        String safeTenant = StringUtils.hasText(tenantId) ? tenantId : "default";

        String key = safeHandler + "#" + safeStatus + "#" + safeTenant;
        Timer timer = timerCache.computeIfAbsent(key, k ->
                Timer.builder(METRIC_NAME)
                        .description(METRIC_DESCRIPTION)
                        .tags(Tags.of(TAG_HANDLER, safeHandler, TAG_STATUS, safeStatus, TAG_TENANT, safeTenant))
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
