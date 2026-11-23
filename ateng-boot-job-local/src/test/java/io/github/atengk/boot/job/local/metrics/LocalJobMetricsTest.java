package io.github.atengk.boot.job.local.metrics;

import io.github.atengk.boot.job.local.config.AtengJobLocalAutoConfiguration;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 云原生 Micrometer 监控指标度量与大盘采集单元测试
 *
 * @author Ateng
 * @since 2026-11-01
 */
@DisplayName("LocalJobMetricsBinder 云原生 Micrometer 监控指标测试")
class LocalJobMetricsTest {

    private SimpleMeterRegistry meterRegistry;
    private LocalJobRegistry localJobRegistry;
    private LocalJobMetricsBinder metricsBinder;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        localJobRegistry = mock(LocalJobRegistry.class);
        metricsBinder = new LocalJobMetricsBinder(localJobRegistry, meterRegistry);
    }

    @Test
    @DisplayName("测试任务执行成功事件正确记录 ateng.job.local.execution Timer 指标")
    void testExecutionSuccessMetricsRecorded() {
        JobSuccessEvent event = new JobSuccessEvent(this, "dailyReconciliationJob", TriggerType.CRON, "trace-101", 350L, "OK");
        metricsBinder.onJobSuccess(event);

        Timer timer = meterRegistry.find(LocalJobMetricsBinder.METRIC_EXECUTION)
                .tag("jobName", "dailyReconciliationJob")
                .tag("status", LocalJobMetricsBinder.STATUS_SUCCESS)
                .tag("triggerType", "CRON")
                .timer();

        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1L);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(350.0);
    }

    @Test
    @DisplayName("测试任务执行失败事件正确记录 ateng.job.local.execution Timer 指标")
    void testExecutionFailedMetricsRecorded() {
        JobFailedEvent event = new JobFailedEvent(this, "orderSyncJob", TriggerType.FIXED_DELAY, "trace-202",
                1200L, 2, new RuntimeException("DB Connection timeout"), "DB Connection timeout");
        metricsBinder.onJobFailed(event);

        Timer timer = meterRegistry.find(LocalJobMetricsBinder.METRIC_EXECUTION)
                .tag("jobName", "orderSyncJob")
                .tag("status", LocalJobMetricsBinder.STATUS_FAILED)
                .tag("triggerType", "FIXED_DELAY")
                .timer();

        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1L);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1200.0);
    }

    @Test
    @DisplayName("测试任务超时熔断事件正确记录 ateng.job.local.execution Timer 指标")
    void testExecutionTimeoutMetricsRecorded() {
        JobTimeoutEvent event = new JobTimeoutEvent(this, "heavyReportJob", TriggerType.MANUAL, "trace-303",
                5000L, Duration.ofSeconds(5), 0);
        metricsBinder.onJobTimeout(event);

        Timer timer = meterRegistry.find(LocalJobMetricsBinder.METRIC_EXECUTION)
                .tag("jobName", "heavyReportJob")
                .tag("status", LocalJobMetricsBinder.STATUS_TIMEOUT)
                .tag("triggerType", "MANUAL")
                .timer();

        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1L);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(5000.0);
    }

    @Test
    @DisplayName("测试任务丢弃事件记录 ateng.job.local.discarded Counter（重叠策略与集群锁抢占）")
    void testDiscardedCounterMetricsRecorded() {
        // 1. 重叠防卫 DISCARD 策略跳过
        JobDiscardedEvent discardEvent = new JobDiscardedEvent(
                this, "fastPollingJob", TriggerType.FIXED_RATE, "trace-404", "Skipped due to overlapping DISCARD policy");
        metricsBinder.onJobDiscarded(discardEvent);

        Counter discardCounter = meterRegistry.find(LocalJobMetricsBinder.METRIC_DISCARDED)
                .tag("jobName", "fastPollingJob")
                .tag("triggerType", "FIXED_RATE")
                .tag("reason", LocalJobMetricsBinder.REASON_OVERLAPPING_DISCARD)
                .counter();

        assertThat(discardCounter).isNotNull();
        assertThat(discardCounter.count()).isEqualTo(1.0);

        // 2. 集群锁被其他节点抢占跳过
        JobDiscardedEvent clusterLockEvent = new JobDiscardedEvent(
                this, "fastPollingJob", TriggerType.FIXED_RATE, "trace-405", "Skipped: cluster lock held by another node");
        metricsBinder.onJobDiscarded(clusterLockEvent);

        Counter lockCounter = meterRegistry.find(LocalJobMetricsBinder.METRIC_DISCARDED)
                .tag("jobName", "fastPollingJob")
                .tag("triggerType", "FIXED_RATE")
                .tag("reason", LocalJobMetricsBinder.REASON_CLUSTER_LOCKED)
                .counter();

        assertThat(lockCounter).isNotNull();
        assertThat(lockCounter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("测试活跃并发度量 ateng.job.local.active Gauge 动态透出")
    void testActiveGaugeMetricsMonitored() {
        when(localJobRegistry.getActiveExecutionCount()).thenReturn(0, 3, 5, 1);

        Gauge gauge = meterRegistry.find(LocalJobMetricsBinder.METRIC_ACTIVE).gauge();
        assertThat(gauge).isNotNull();

        assertThat(gauge.value()).isEqualTo(0.0);
        assertThat(gauge.value()).isEqualTo(3.0);
        assertThat(gauge.value()).isEqualTo(5.0);
        assertThat(gauge.value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("测试自动装配：存在 MeterRegistry 时成功装配 LocalJobMetricsBinder")
    void testAutoConfigurationWithMeterRegistryPresent() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AtengJobLocalAutoConfiguration.class))
                .withUserConfiguration(MockMetricsConfig.class)
                .withPropertyValues("ateng.boot.job.local.metrics.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(LocalJobMetricsBinder.class);
                    LocalJobMetricsBinder binder = context.getBean(LocalJobMetricsBinder.class);
                    assertThat(binder.getMeterRegistry()).isNotNull();
                });
    }

    @Test
    @DisplayName("测试自动装配：配置显式禁用 metrics.enabled=false 时不装配 Binder")
    void testAutoConfigurationDisabledWhenPropertyFalse() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AtengJobLocalAutoConfiguration.class))
                .withUserConfiguration(MockMetricsConfig.class)
                .withPropertyValues("ateng.boot.job.local.metrics.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(LocalJobMetricsBinder.class);
                });
    }

    @Test
    @DisplayName("测试自动装配：环境中无 MeterRegistry 时不装配 Binder")
    void testAutoConfigurationSkippedWhenMeterRegistryAbsent() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AtengJobLocalAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(LocalJobMetricsBinder.class);
                });
    }

    @Configuration
    static class MockMetricsConfig {
        @Bean
        public MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
