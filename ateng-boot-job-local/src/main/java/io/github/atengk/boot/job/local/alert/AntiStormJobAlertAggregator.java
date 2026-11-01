package io.github.atengk.boot.job.local.alert;

import io.github.atengk.boot.job.local.enums.TriggerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 三阶任务告警防风暴聚合器
 * <p>
 * 保护企业微信、钉钉、飞书等群机器人免受秒级高频失败任务引发的“告警风暴”轰炸与平台封禁：
 * <ol>
 *   <li><b>首发即送 (Immediate First-Strike)</b>：健康任务首次出现失败或超时熔断时，立即向外部发送告警；</li>
 *   <li><b>静默窗口期聚合 (Sliding Window Suppression)</b>：在滑动静默窗口内（默认 5 分钟），后续失败不再重复打扰，而在原子计数器中累计错误次数并持续采样最新异常；静默期满仍未恢复则发出累计提醒；</li>
 *   <li><b>故障自愈闭环 (Self-Healing Recovery Notice)</b>：任务成功恢复执行时，自动发送绿色自愈通知，详述故障持续时长与累计抑制失败次数，并重置健康状态。</li>
 * </ol>
 * </p>
 *
 * @author Ateng
 * @since 2026-11-01
 */
public class AntiStormJobAlertAggregator {

    private static final Logger log = LoggerFactory.getLogger(AntiStormJobAlertAggregator.class);

    private final JobAlertHandler delegate;
    private final boolean enabled;
    private final Duration silenceWindow;
    private final boolean recoveryNotice;
    private final ConcurrentMap<String, JobAlertState> stateMap = new ConcurrentHashMap<>();

    public AntiStormJobAlertAggregator(JobAlertHandler delegate) {
        this(delegate, true, Duration.ofMinutes(5), true);
    }

    public AntiStormJobAlertAggregator(JobAlertHandler delegate, boolean enabled, Duration silenceWindow, boolean recoveryNotice) {
        this.delegate = delegate;
        this.enabled = enabled;
        this.silenceWindow = (silenceWindow != null && !silenceWindow.isNegative() && !silenceWindow.isZero())
                ? silenceWindow
                : Duration.ofMinutes(5);
        this.recoveryNotice = recoveryNotice;
    }

    /**
     * 处理任务异常告警事件（首发即送 / 静默抑制与聚合）
     *
     * @param context 原始告警上下文
     */
    public void handleAlert(JobAlertContext context) {
        if (context == null || context.getJobName() == null || context.getJobName().isBlank()) {
            return;
        }
        if (!enabled) {
            if (delegate != null) {
                delegate.handleAlert(context);
            }
            return;
        }

        String jobName = context.getJobName();
        Instant now = context.getTimestamp() != null ? context.getTimestamp() : Instant.now();

        // 尝试建立首次故障状态
        JobAlertState state = stateMap.compute(jobName, (k, existing) -> {
            if (existing == null) {
                return new JobAlertState(jobName, now, context.getErrorMsg(), context.getThrowable(), context.getTraceId());
            }
            existing.incrementFailure(now, context.getErrorMsg(), context.getThrowable(), context.getTraceId());
            return existing;
        });

        // 首次故障：立即放行触发即时告警
        if (state.getFailureCount() == 1) {
            log.info("Dispatched immediate first-strike alert for job [{}]", jobName);
            if (delegate != null) {
                delegate.handleAlert(context);
            }
            return;
        }

        // 后续故障：通过状态机原子跃迁判断是否跨越静默期并触发阶段提醒
        if (state.tryTriggerPeriodicAlert(now, silenceWindow)) {
            String periodicSummary = state.buildPeriodicSummary(now, context.getErrorMsg());
            log.warn("Job [{}] still failing after silence window (total failures: {}), dispatching periodic summary alert",
                    jobName, state.getFailureCount());

            JobAlertContext aggregatedContext = context.toBuilder()
                    .errorMsg(periodicSummary)
                    .retryCount(state.getFailureCount())
                    .timestamp(now)
                    .build();
            if (delegate != null) {
                delegate.handleAlert(aggregatedContext);
            }
        } else {
            // 静默期内：抑制告警
            log.debug("Job [{}] failure alert suppressed by anti-storm aggregator (suppressed count: {})",
                    jobName, state.getFailureCount());
        }
    }

    /**
     * 处理任务执行成功事件（检测并触发故障自愈恢复闭环）
     *
     * @param jobName     任务名称
     * @param triggerType 触发方式
     * @param traceId     链路追踪号
     * @param durationMs  执行耗时
     */
    public void handleSuccess(String jobName, TriggerType triggerType, String traceId, long durationMs) {
        if (jobName == null || jobName.isBlank() || !enabled) {
            return;
        }

        JobAlertState state = stateMap.remove(jobName);
        if (state != null) {
            Instant now = Instant.now();
            Duration outageDuration = Duration.between(state.getFirstFailureTime(), now);
            int totalFailures = state.getFailureCount();

            log.info("Job [{}] recovered from failure! Outage: {}, Total suppressed failures: {}",
                    jobName, formatDuration(outageDuration), totalFailures);

            if (recoveryNotice && delegate != null) {
                String recoverySummary = state.buildRecoverySummary(now);

                JobAlertContext recoveryContext = JobAlertContext.builder()
                        .jobName(jobName)
                        .triggerType(triggerType)
                        .traceId(traceId)
                        .alertType(JobAlertContext.ALERT_TYPE_RECOVERY)
                        .durationMs(durationMs)
                        .retryCount(0)
                        .errorMsg(recoverySummary)
                        .timestamp(now)
                        .build();

                try {
                    delegate.handleAlert(recoveryContext);
                } catch (Throwable t) {
                    log.warn("Failed to dispatch recovery notice for job [{}]: {}", jobName, t.getMessage());
                }
            }
        }
    }

    /**
     * 判断指定任务是否当前处于故障告警抑制状态
     *
     * @param jobName 任务名称
     * @return 是否处于抑制中
     */
    public boolean isSuppressed(String jobName) {
        if (jobName == null || jobName.isBlank()) {
            return false;
        }
        JobAlertState state = stateMap.get(jobName);
        return state != null && state.getFailureCount() > 1;
    }

    /**
     * 获取指定任务当前累计的失败总次数（若处于健康状态返回 0）
     *
     * @param jobName 任务名称
     * @return 累计失败次数
     */
    public int getSuppressedFailureCount(String jobName) {
        if (jobName == null || jobName.isBlank()) {
            return 0;
        }
        JobAlertState state = stateMap.get(jobName);
        return state != null ? state.getFailureCount() : 0;
    }

    /**
     * 获取指定任务当前的故障抑制状态快照（健康时返回 null）
     *
     * @param jobName 任务名称
     * @return 状态快照
     */
    public JobAlertState getState(String jobName) {
        if (jobName == null || jobName.isBlank()) {
            return null;
        }
        return stateMap.get(jobName);
    }

    /**
     * 重置指定任务的告警抑制状态
     *
     * @param jobName 任务名称
     */
    public void reset(String jobName) {
        if (jobName != null && !jobName.isBlank()) {
            stateMap.remove(jobName);
        }
    }

    /**
     * 清空所有任务的告警抑制状态
     */
    public void clearAll() {
        stateMap.clear();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Duration getSilenceWindow() {
        return silenceWindow;
    }

    public boolean isRecoveryNotice() {
        return recoveryNotice;
    }

    private static String formatDuration(Duration duration) {
        if (duration == null) {
            return "0 秒";
        }
        long seconds = duration.toSeconds();
        if (seconds < 60) {
            return Math.max(1, seconds) + " 秒";
        }
        long minutes = seconds / 60;
        long remainingSec = seconds % 60;
        if (remainingSec == 0) {
            return minutes + " 分钟";
        }
        return minutes + " 分 " + remainingSec + " 秒";
    }

    /**
     * 单个定时任务的告警抑制与故障聚合内部状态机
     */
    public static class JobAlertState {
        private final String jobName;
        private final Instant firstFailureTime;
        private volatile Instant lastFailureTime;
        private volatile Instant lastAlertTime;
        private final AtomicInteger failureCount = new AtomicInteger(1);
        private volatile String latestErrorMsg;
        private volatile Throwable latestThrowable;
        private volatile String latestTraceId;

        public JobAlertState(String jobName, Instant failureTime, String errorMsg, Throwable throwable, String traceId) {
            this.jobName = jobName;
            this.firstFailureTime = failureTime;
            this.lastFailureTime = failureTime;
            this.lastAlertTime = failureTime;
            this.latestErrorMsg = errorMsg;
            this.latestThrowable = throwable;
            this.latestTraceId = traceId;
        }

        public int incrementFailure(Instant failureTime, String errorMsg, Throwable throwable, String traceId) {
            this.lastFailureTime = failureTime;
            this.latestErrorMsg = errorMsg;
            this.latestThrowable = throwable;
            this.latestTraceId = traceId;
            return this.failureCount.incrementAndGet();
        }

        /**
         * 尝试触发周期性持续故障阶段聚合提醒（原子 Check-Then-Act 状态跃迁）
         *
         * @param now           当前时间戳
         * @param silenceWindow 静默窗口时长
         * @return true 表示成功跨越静默窗口并原子更新 lastAlertTime；false 表示处于静默期内应抑制
         */
        public synchronized boolean tryTriggerPeriodicAlert(Instant now, Duration silenceWindow) {
            if (Duration.between(lastAlertTime, now).compareTo(silenceWindow) >= 0) {
                this.lastAlertTime = now;
                return true;
            }
            return false;
        }

        /**
         * 生成持续故障聚合提醒文案
         *
         * @param now           当前时间戳
         * @param errorMsg      当前最新错误信息
         * @return 格式化摘要文案
         */
        public String buildPeriodicSummary(Instant now, String errorMsg) {
            Duration totalOutage = Duration.between(firstFailureTime, now);
            return String.format("【持续故障提醒】故障已持续 %s，期间累计异常 %d 次。最新错误: %s",
                    formatDuration(totalOutage), failureCount.get(), errorMsg);
        }

        /**
         * 生成故障自愈恢复通知文案
         *
         * @param now 当前自愈时间戳
         * @return 格式化自愈文案
         */
        public String buildRecoverySummary(Instant now) {
            Duration outageDuration = Duration.between(firstFailureTime, now);
            return String.format("故障已自愈恢复。持续故障时长: %s，期间累计发生 %d 次异常后成功自愈",
                    formatDuration(outageDuration), failureCount.get());
        }

        public int getFailureCount() {
            return failureCount.get();
        }

        public String getJobName() {
            return jobName;
        }

        public Instant getFirstFailureTime() {
            return firstFailureTime;
        }

        public Instant getLastFailureTime() {
            return lastFailureTime;
        }

        public Instant getLastAlertTime() {
            return lastAlertTime;
        }

        public void setLastAlertTime(Instant lastAlertTime) {
            this.lastAlertTime = lastAlertTime;
        }

        public String getLatestErrorMsg() {
            return latestErrorMsg;
        }

        public Throwable getLatestThrowable() {
            return latestThrowable;
        }

        public String getLatestTraceId() {
            return latestTraceId;
        }
    }
}
