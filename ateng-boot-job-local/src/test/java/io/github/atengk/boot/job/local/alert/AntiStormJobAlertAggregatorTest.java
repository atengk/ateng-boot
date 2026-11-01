package io.github.atengk.boot.job.local.alert;

import io.github.atengk.boot.job.local.enums.TriggerType;
import io.github.atengk.boot.job.local.event.JobFailedEvent;
import io.github.atengk.boot.job.local.event.JobSuccessEvent;
import io.github.atengk.boot.job.local.event.JobTimeoutEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三阶告警防风暴聚合器与故障自愈恢复闭环单元测试
 *
 * @author Ateng
 * @since 2026-11-01
 */
@DisplayName("AntiStormJobAlertAggregator 三阶告警防风暴与故障自愈测试")
class AntiStormJobAlertAggregatorTest {

    private RecordingJobAlertHandler recordingHandler;

    @BeforeEach
    void setUp() {
        recordingHandler = new RecordingJobAlertHandler();
    }

    @Test
    @DisplayName("测试首发即送：健康任务首次发生异常时立即放行发送告警")
    void testImmediateFirstStrike() {
        AntiStormJobAlertAggregator aggregator = new AntiStormJobAlertAggregator(
                recordingHandler, true, Duration.ofMinutes(5), true);

        JobAlertContext context = JobAlertContext.builder()
                .jobName("orderSyncJob")
                .triggerType(TriggerType.CRON)
                .traceId("trace-1001")
                .alertType(JobAlertContext.ALERT_TYPE_FAILED)
                .durationMs(120)
                .errorMsg("Database timeout")
                .timestamp(Instant.now())
                .build();

        aggregator.handleAlert(context);

        assertThat(recordingHandler.getAlerts()).hasSize(1);
        JobAlertContext received = recordingHandler.getAlerts().get(0);
        assertThat(received.getJobName()).isEqualTo("orderSyncJob");
        assertThat(received.getAlertType()).isEqualTo(JobAlertContext.ALERT_TYPE_FAILED);
        assertThat(received.getErrorMsg()).isEqualTo("Database timeout");
    }

    @Test
    @DisplayName("测试静默窗口期抑制：同一任务连续高频失败，后续报警被静默抑制并累计错误次数")
    void testSlidingWindowSuppression() {
        AntiStormJobAlertAggregator aggregator = new AntiStormJobAlertAggregator(
                recordingHandler, true, Duration.ofMinutes(5), true);

        Instant baseTime = Instant.now();

        // 连续触发 10 次失败事件（均在 5 分钟静默窗口内）
        for (int i = 1; i <= 10; i++) {
            JobAlertContext ctx = JobAlertContext.builder()
                    .jobName("paymentCheckJob")
                    .triggerType(TriggerType.FIXED_RATE)
                    .traceId("trace-" + i)
                    .alertType(JobAlertContext.ALERT_TYPE_FAILED)
                    .durationMs(50)
                    .errorMsg("Remote service 503 error, attempt " + i)
                    .timestamp(baseTime.plusSeconds(i * 5))
                    .build();
            aggregator.handleAlert(ctx);
        }

        // 仅第 1 次被放行，后续 9 次均被静默抑制
        assertThat(recordingHandler.getAlerts()).hasSize(1);
        assertThat(recordingHandler.getAlerts().get(0).getTraceId()).isEqualTo("trace-1");

        // 状态检查
        AntiStormJobAlertAggregator.JobAlertState state = aggregator.getState("paymentCheckJob");
        assertThat(state).isNotNull();
        assertThat(state.getFailureCount()).isEqualTo(10);
        assertThat(state.getLatestErrorMsg()).isEqualTo("Remote service 503 error, attempt 10");
    }

    @Test
    @DisplayName("测试静默期满阶段聚合提醒：超过静默窗口期仍未自愈时发出聚合汇总告警")
    void testPeriodicSummaryAlertAfterSilenceWindow() {
        // 设置极短静默窗口 100ms
        AntiStormJobAlertAggregator aggregator = new AntiStormJobAlertAggregator(
                recordingHandler, true, Duration.ofMillis(100), true);

        Instant t0 = Instant.now();

        // 首次失败 -> 首发即送
        aggregator.handleAlert(JobAlertContext.builder()
                .jobName("inventorySyncJob")
                .triggerType(TriggerType.CRON)
                .traceId("trace-1")
                .alertType(JobAlertContext.ALERT_TYPE_FAILED)
                .durationMs(80)
                .errorMsg("First error")
                .timestamp(t0)
                .build());

        assertThat(recordingHandler.getAlerts()).hasSize(1);

        // 静默期内失败（t0 + 20ms）-> 抑制
        aggregator.handleAlert(JobAlertContext.builder()
                .jobName("inventorySyncJob")
                .triggerType(TriggerType.CRON)
                .traceId("trace-2")
                .alertType(JobAlertContext.ALERT_TYPE_FAILED)
                .durationMs(80)
                .errorMsg("Second error")
                .timestamp(t0.plusMillis(20))
                .build());

        assertThat(recordingHandler.getAlerts()).hasSize(1);

        // 静默期满后失败（t0 + 150ms > 100ms）-> 阶段聚合提醒
        aggregator.handleAlert(JobAlertContext.builder()
                .jobName("inventorySyncJob")
                .triggerType(TriggerType.CRON)
                .traceId("trace-3")
                .alertType(JobAlertContext.ALERT_TYPE_FAILED)
                .durationMs(90)
                .errorMsg("Third error")
                .timestamp(t0.plusMillis(150))
                .build());

        assertThat(recordingHandler.getAlerts()).hasSize(2);
        JobAlertContext summaryAlert = recordingHandler.getAlerts().get(1);
        assertThat(summaryAlert.getErrorMsg()).contains("【持续故障提醒】");
        assertThat(summaryAlert.getErrorMsg()).contains("期间累计异常 3 次");
        assertThat(summaryAlert.getRetryCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("测试故障自愈恢复闭环：任务成功恢复后发送绿色自愈通知，并重置健康状态")
    void testSelfHealingRecoveryNotice() {
        AntiStormJobAlertAggregator aggregator = new AntiStormJobAlertAggregator(
                recordingHandler, true, Duration.ofMinutes(5), true);

        Instant start = Instant.now();

        // 连续 5 次失败
        for (int i = 1; i <= 5; i++) {
            aggregator.handleAlert(JobAlertContext.builder()
                    .jobName("cacheWarmupJob")
                    .triggerType(TriggerType.CRON)
                    .traceId("trace-" + i)
                    .alertType(JobAlertContext.ALERT_TYPE_FAILED)
                    .durationMs(100)
                    .errorMsg("Redis connection timeout: " + i)
                    .timestamp(start.plusSeconds(i))
                    .build());
        }

        // 仅第 1 次报警送出
        assertThat(recordingHandler.getAlerts()).hasSize(1);
        assertThat(aggregator.getState("cacheWarmupJob")).isNotNull();

        // 任务恢复成功执行
        aggregator.handleSuccess("cacheWarmupJob", TriggerType.CRON, "trace-success-999", 65);

        // 接收到故障自愈恢复通知
        assertThat(recordingHandler.getAlerts()).hasSize(2);
        JobAlertContext recoveryAlert = recordingHandler.getAlerts().get(1);
        assertThat(recoveryAlert.getJobName()).isEqualTo("cacheWarmupJob");
        assertThat(recoveryAlert.getAlertType()).isEqualTo(JobAlertContext.ALERT_TYPE_RECOVERY);
        assertThat(recoveryAlert.getErrorMsg()).contains("故障已自愈恢复");
        assertThat(recoveryAlert.getErrorMsg()).contains("5 次异常");
        assertThat(recoveryAlert.getTraceId()).isEqualTo("trace-success-999");
        assertThat(recoveryAlert.getDurationMs()).isEqualTo(65);

        // 聚合器状态被重置清理
        assertThat(aggregator.getState("cacheWarmupJob")).isNull();

        // 再次发生失败时，重新作为全新故障触发首发即送
        aggregator.handleAlert(JobAlertContext.builder()
                .jobName("cacheWarmupJob")
                .triggerType(TriggerType.CRON)
                .traceId("trace-new-fail")
                .alertType(JobAlertContext.ALERT_TYPE_FAILED)
                .durationMs(200)
                .errorMsg("New network issue")
                .timestamp(Instant.now())
                .build());

        assertThat(recordingHandler.getAlerts()).hasSize(3);
        assertThat(recordingHandler.getAlerts().get(2).getTraceId()).isEqualTo("trace-new-fail");
    }

    @Test
    @DisplayName("测试未发生故障的任务执行成功不产生伪自愈通知")
    void testNormalJobSuccessProducesNoRecoveryNotice() {
        AntiStormJobAlertAggregator aggregator = new AntiStormJobAlertAggregator(
                recordingHandler, true, Duration.ofMinutes(5), true);

        aggregator.handleSuccess("healthyJob", TriggerType.CRON, "trace-100", 30);

        assertThat(recordingHandler.getAlerts()).isEmpty();
    }

    @Test
    @DisplayName("测试禁用防风暴聚合器时的直通行为")
    void testDisabledAggregatorPassThrough() {
        AntiStormJobAlertAggregator aggregator = new AntiStormJobAlertAggregator(
                recordingHandler, false, Duration.ofMinutes(5), true);

        // 连续 3 次失败
        for (int i = 1; i <= 3; i++) {
            aggregator.handleAlert(JobAlertContext.builder()
                    .jobName("directJob")
                    .triggerType(TriggerType.CRON)
                    .traceId("trace-" + i)
                    .alertType(JobAlertContext.ALERT_TYPE_FAILED)
                    .durationMs(50)
                    .errorMsg("Fail " + i)
                    .timestamp(Instant.now())
                    .build());
        }

        // 每次失败均直通，无抑制
        assertThat(recordingHandler.getAlerts()).hasSize(3);

        // 成功事件也不会发出自愈通知
        aggregator.handleSuccess("directJob", TriggerType.CRON, "trace-success", 40);
        assertThat(recordingHandler.getAlerts()).hasSize(3);
    }

    @Test
    @DisplayName("测试禁用自愈通知时仅清理状态不发恢复通知")
    void testDisabledRecoveryNotice() {
        AntiStormJobAlertAggregator aggregator = new AntiStormJobAlertAggregator(
                recordingHandler, true, Duration.ofMinutes(5), false);

        aggregator.handleAlert(JobAlertContext.builder()
                .jobName("silentRecoveryJob")
                .triggerType(TriggerType.CRON)
                .traceId("trace-1")
                .alertType(JobAlertContext.ALERT_TYPE_FAILED)
                .durationMs(50)
                .errorMsg("Error")
                .timestamp(Instant.now())
                .build());

        assertThat(recordingHandler.getAlerts()).hasSize(1);
        assertThat(aggregator.getState("silentRecoveryJob")).isNotNull();

        // 成功自愈
        aggregator.handleSuccess("silentRecoveryJob", TriggerType.CRON, "trace-2", 40);

        // 未发送自愈通知，且状态已被清理
        assertThat(recordingHandler.getAlerts()).hasSize(1);
        assertThat(aggregator.getState("silentRecoveryJob")).isNull();
    }

    @Test
    @DisplayName("测试与 LocalJobAlertListener 领域事件协同链路")
    void testIntegrationWithLocalJobAlertListener() {
        AntiStormJobAlertAggregator aggregator = new AntiStormJobAlertAggregator(
                recordingHandler, true, Duration.ofMinutes(5), true);
        LocalJobAlertListener listener = new LocalJobAlertListener(recordingHandler, aggregator);

        // 1. 发布失败事件 -> 首发即送
        listener.onJobFailed(new JobFailedEvent(this, "eventJob", TriggerType.CRON, "trace-fail-1",
                120, 0, new RuntimeException("DB down"), "DB Error"));

        assertThat(recordingHandler.getAlerts()).hasSize(1);
        assertThat(recordingHandler.getAlerts().get(0).getAlertType()).isEqualTo(JobAlertContext.ALERT_TYPE_FAILED);

        // 2. 紧接着发布超时事件 -> 静默抑制
        listener.onJobTimeout(new JobTimeoutEvent(this, "eventJob", TriggerType.CRON, "trace-timeout-2",
                3000, Duration.ofSeconds(3), 0));

        assertThat(recordingHandler.getAlerts()).hasSize(1);

        // 3. 发布成功事件 -> 触发自愈通知
        listener.onJobSuccess(new JobSuccessEvent(this, "eventJob", TriggerType.CRON, "trace-succ-3",
                80, "OK"));

        assertThat(recordingHandler.getAlerts()).hasSize(2);
        assertThat(recordingHandler.getAlerts().get(1).getAlertType()).isEqualTo(JobAlertContext.ALERT_TYPE_RECOVERY);
        assertThat(recordingHandler.getAlerts().get(1).getErrorMsg()).contains("自愈恢复");
    }

    @Test
    @DisplayName("测试空值与空白 jobName 防卫：防御性保底不抛出 NPE")
    void testNullAndBlankInputsSafety() {
        AntiStormJobAlertAggregator aggregator = new AntiStormJobAlertAggregator(
                recordingHandler, true, Duration.ofMinutes(5), true);

        // 1. 测试 null context 与 jobName 为空
        aggregator.handleAlert(null);
        aggregator.handleAlert(JobAlertContext.builder().jobName(null).build());
        aggregator.handleAlert(JobAlertContext.builder().jobName("   ").build());
        assertThat(recordingHandler.getAlerts()).isEmpty();

        // 2. 测试查询与操作 API 空防御
        assertThat(aggregator.isSuppressed(null)).isFalse();
        assertThat(aggregator.isSuppressed("")).isFalse();
        assertThat(aggregator.getSuppressedFailureCount(null)).isEqualTo(0);
        assertThat(aggregator.getSuppressedFailureCount("   ")).isEqualTo(0);
        assertThat(aggregator.getState(null)).isNull();
        assertThat(aggregator.getState("")).isNull();

        // 3. 测试 handleSuccess 与 reset 空防御
        aggregator.handleSuccess(null, TriggerType.MANUAL, "trace-x", 10);
        aggregator.handleSuccess("   ", TriggerType.MANUAL, "trace-x", 10);
        aggregator.reset(null);
        aggregator.reset("");
        assertThat(recordingHandler.getAlerts()).isEmpty();
    }

    private static class RecordingJobAlertHandler implements JobAlertHandler {
        private final List<JobAlertContext> alerts = new CopyOnWriteArrayList<>();

        @Override
        public void handleAlert(JobAlertContext context) {
            alerts.add(context);
        }

        public List<JobAlertContext> getAlerts() {
            return alerts;
        }
    }
}
