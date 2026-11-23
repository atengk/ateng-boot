package io.github.atengk.boot.job.xxl.alert;

import io.github.atengk.boot.job.xxl.event.XxlJobAlertEvent;
import io.github.atengk.boot.job.xxl.properties.XxlJobProperties;
import io.github.atengk.boot.notification.model.CardColor;
import io.github.atengk.boot.notification.model.CardMessage;
import io.github.atengk.boot.notification.template.NotificationTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.util.StringUtils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * XXL-Job 三阶告警防风暴聚合与自愈引擎
 *
 * <p>实现工业级告警治理状态机：
 * <ol>
 *     <li><b>首发告警 (First Failure)</b>：任务首次失败时立即推送红色告警卡片并广播事件；</li>
 *     <li><b>风暴抑制与周期摘要 (Suppression & Summary)</b>：持续失败期间抑制中间重复骚扰，达到周期或阈值推送橙色聚合摘要卡片；</li>
 *     <li><b>自愈恢复通知 (Self-Healing Recovery)</b>：连续失败后首次成功执行推送绿色自愈卡片并重置健康状态。</li>
 * </ol>
 * 依托 {@link NotificationTemplate} 协同通知门面异步推送，无通知组件时平滑降级为 Spring 标准领域事件与结构化日志。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class XxlJobAlertAggregator {

    private static final Logger log = LoggerFactory.getLogger(XxlJobAlertAggregator.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final XxlJobProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final NotificationTemplate notificationTemplate;

    private final ConcurrentMap<String, JobAlertState> stateMap = new ConcurrentHashMap<>();

    public XxlJobAlertAggregator(
            XxlJobProperties properties,
            ApplicationEventPublisher eventPublisher,
            NotificationTemplate notificationTemplate) {
        this.properties = properties != null ? properties : new XxlJobProperties();
        this.eventPublisher = eventPublisher;
        this.notificationTemplate = notificationTemplate;
    }

    public XxlJobAlertAggregator(
            XxlJobProperties properties,
            NotificationTemplate notificationTemplate,
            ApplicationEventPublisher eventPublisher) {
        this(properties, eventPublisher, notificationTemplate);
    }

    /**
     * 记录任务执行失败并触发三阶告警决策 (便捷重载)
     *
     * @param jobHandler 任务 Handler 标识
     * @param throwable  抛出的异常信息
     */
    public void onJobFailure(String jobHandler, Throwable throwable) {
        onJobFailure(jobHandler, null, null, 0, throwable);
    }

    /**
     * 记录任务执行失败并触发三阶告警决策
     *
     * @param jobHandler 任务 Handler 标识
     * @param param      执行入参
     * @param traceId    全链路追踪 traceId
     * @param durationMs 执行耗时 (毫秒)
     * @param throwable  抛出的异常信息
     */
    public void onJobFailure(String jobHandler, String param, String traceId, long durationMs, Throwable throwable) {
        onJobFailure(jobHandler, param, traceId, durationMs, throwable, true, -1);
    }

    /**
     * 记录任务执行失败并触发三阶告警决策（支持方法级告警开关与抑制阈值覆盖）
     *
     * @param jobHandler             任务 Handler 标识
     * @param param                  执行入参
     * @param traceId                全链路追踪 traceId
     * @param durationMs             执行耗时 (毫秒)
     * @param throwable              抛出的异常信息
     * @param alertEnabled           方法级告警开关
     * @param alertSuppressThreshold 方法级告警抑制阈值 (-1 表示继承全局配置)
     */
    public void onJobFailure(String jobHandler, String param, String traceId, long durationMs, Throwable throwable,
                             boolean alertEnabled, int alertSuppressThreshold) {
        if (!properties.isAlertEnabled() || !alertEnabled) {
            return;
        }

        String key = StringUtils.hasText(jobHandler) ? jobHandler : "defaultHandler";
        JobAlertState state = stateMap.computeIfAbsent(key, k -> new JobAlertState());

        long now = System.currentTimeMillis();
        Duration suppressDuration = properties.getAlertSuppressDuration() != null
                ? properties.getAlertSuppressDuration()
                : Duration.ofMinutes(5);
        int threshold = alertSuppressThreshold > 0
                ? alertSuppressThreshold
                : (properties.getAlertSuppressThreshold() > 0 ? properties.getAlertSuppressThreshold() : 10);

        FailureTransition transition = state.recordFailure(now, threshold, suppressDuration);
        if (transition.alertType() == XxlJobAlertEvent.AlertType.INITIAL_FAILURE) {
            // 阶段一：首发失败立即推送
            dispatchAlert(XxlJobAlertEvent.AlertType.INITIAL_FAILURE, key, param, traceId, durationMs, 1, Duration.ZERO, throwable);
        } else if (transition.alertType() == XxlJobAlertEvent.AlertType.FAILURE_SUMMARY) {
            // 阶段二：达到周期或连续失败阈值推送聚合摘要
            dispatchAlert(XxlJobAlertEvent.AlertType.FAILURE_SUMMARY, key, param, traceId, durationMs,
                    transition.totalFailures(), transition.failureDuration(), throwable);
        } else {
            log.debug("XXL-Job 任务 [{}] 持续失败中，已平滑抑制第 {} 次重复告警", key, transition.totalFailures());
        }
    }

    /**
     * 记录任务成功执行并在曾经故障后触发自愈恢复通知 (便捷重载)
     *
     * @param jobHandler 任务 Handler 标识
     */
    public void onJobSuccess(String jobHandler) {
        onJobSuccess(jobHandler, null, null, 0);
    }

    /**
     * 记录任务成功执行并在曾经故障后触发自愈恢复通知
     *
     * @param jobHandler 任务 Handler 标识
     * @param param      执行入参
     * @param traceId    全链路追踪 traceId
     * @param durationMs 执行耗时 (毫秒)
     */
    public void onJobSuccess(String jobHandler, String param, String traceId, long durationMs) {
        onJobSuccess(jobHandler, param, traceId, durationMs, true);
    }

    /**
     * 记录任务成功执行并在曾经故障后触发自愈恢复通知（支持方法级告警开关）
     *
     * @param jobHandler   任务 Handler 标识
     * @param param        执行入参
     * @param traceId      全链路追踪 traceId
     * @param durationMs   执行耗时 (毫秒)
     * @param alertEnabled 方法级告警开关
     */
    public void onJobSuccess(String jobHandler, String param, String traceId, long durationMs, boolean alertEnabled) {
        if (!properties.isAlertEnabled() || !alertEnabled) {
            return;
        }

        String key = StringUtils.hasText(jobHandler) ? jobHandler : "defaultHandler";
        JobAlertState state = stateMap.get(key);
        if (state == null) {
            return;
        }

        // 阶段三：在状态锁内原子判定并重置故障状态，避免高并发下重复自愈推送或统计撕裂
        RecoveryTransition recovery = state.consumeRecovery(System.currentTimeMillis());
        if (recovery == null) {
            return;
        }

        dispatchAlert(XxlJobAlertEvent.AlertType.RECOVERY, key, param, traceId, durationMs,
                recovery.totalFailures(), recovery.failureDuration(), null);
    }

    private void dispatchAlert(
            XxlJobAlertEvent.AlertType alertType,
            String jobHandler,
            String param,
            String traceId,
            long durationMs,
            int failureCount,
            Duration failureDuration,
            Throwable throwable) {

        // 1. 发布 Spring 标准领域事件
        if (eventPublisher != null) {
            try {
                eventPublisher.publishEvent(new XxlJobAlertEvent(
                        this, alertType, jobHandler, param, traceId, durationMs, failureCount, failureDuration, throwable
                ));
            } catch (Exception e) {
                log.error("广播 XxlJobAlertEvent 事件发生异常", e);
            }
        }

        // 2. 结构化日志记录
        switch (alertType) {
            case INITIAL_FAILURE -> log.error("🚨 [XXL-Job 首发告警] 任务执行失败: jobHandler={}, traceId={}, cost={}ms, error={}",
                    jobHandler, traceId, durationMs, throwable != null ? throwable.getMessage() : "unknown");
            case FAILURE_SUMMARY -> log.warn("⚠️ [XXL-Job 聚合摘要] 任务持续失败: jobHandler={}, traceId={}, 累计失败={}次, 故障持续={}秒, 最新异常={}",
                    jobHandler, traceId, failureCount, failureDuration.toSeconds(), throwable != null ? throwable.getMessage() : "unknown");
            case RECOVERY -> log.info("✅ [XXL-Job 自愈恢复] 任务故障已恢复正常: jobHandler={}, traceId={}, 曾累计失败={}次, 故障历时={}秒",
                    jobHandler, traceId, failureCount, failureDuration.toSeconds());
        }

        // 3. 异步推送协同机器人富文本卡片 (若引入了 ateng-boot-notification)
        if (notificationTemplate != null) {
            try {
                CardMessage card = buildCardMessage(alertType, jobHandler, param, traceId, durationMs, failureCount, failureDuration, throwable);
                notificationTemplate.sendAsync(card);
            } catch (Throwable t) {
                log.warn("通过 NotificationTemplate 发送 XXL-Job 告警卡片失败，已降级忽略: {}", t.getMessage());
            }
        }
    }

    private CardMessage buildCardMessage(
            XxlJobAlertEvent.AlertType alertType,
            String jobHandler,
            String param,
            String traceId,
            long durationMs,
            int failureCount,
            Duration failureDuration,
            Throwable throwable) {

        String timeStr = LocalDateTime.now().format(TIME_FORMATTER);
        StringBuilder content = new StringBuilder();

        switch (alertType) {
            case INITIAL_FAILURE -> {
                content.append("**任务标识**: `").append(jobHandler).append("`\n\n");
                content.append("**链路追踪**: `").append(traceId != null ? traceId : "-").append("`\n\n");
                content.append("**调度入参**: `").append(param != null ? param : "-").append("`\n\n");
                content.append("**执行耗时**: `").append(durationMs).append(" ms`\n\n");
                content.append("**发生时间**: ").append(timeStr).append("\n\n");
                content.append("**异常堆栈**:\n```text\n")
                        .append(extractStackTraceSnippet(throwable))
                        .append("\n```");
                return CardMessage.of("🚨 [XXL-Job 任务执行异常]", content.toString())
                        .headerColor(CardColor.RED);
            }
            case FAILURE_SUMMARY -> {
                content.append("**任务标识**: `").append(jobHandler).append("`\n\n");
                content.append("**累计失败**: `").append(failureCount).append(" 次`\n\n");
                content.append("**故障持续**: `").append(failureDuration.toSeconds()).append(" 秒`\n\n");
                content.append("**链路追踪**: `").append(traceId != null ? traceId : "-").append("`\n\n");
                content.append("**摘要时间**: ").append(timeStr).append("\n\n");
                content.append("**最新异常**:\n```text\n")
                        .append(extractStackTraceSnippet(throwable))
                        .append("\n```");
                return CardMessage.of("⚠️ [XXL-Job 持续失败聚合摘要]", content.toString())
                        .headerColor(CardColor.ORANGE);
            }
            case RECOVERY -> {
                content.append("**任务标识**: `").append(jobHandler).append("`\n\n");
                content.append("**运行状态**: `已恢复正常执行`\n\n");
                content.append("**期间累计失败**: `").append(failureCount).append(" 次`\n\n");
                content.append("**故障总历时**: `").append(failureDuration.toSeconds()).append(" 秒`\n\n");
                content.append("**恢复时间**: ").append(timeStr);
                return CardMessage.of("✅ [XXL-Job 任务故障自愈恢复]", content.toString())
                        .headerColor(CardColor.GREEN);
            }
            default -> throw new IllegalArgumentException("Unknown AlertType: " + alertType);
        }
    }

    private String extractStackTraceSnippet(Throwable throwable) {
        if (throwable == null) {
            return "No exception stack trace available.";
        }
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            throwable.printStackTrace(pw);
        }
        String fullTrace = sw.toString();
        // 截取前 500 个字符防超长溢出
        return fullTrace.length() > 500 ? fullTrace.substring(0, 500) + "\n... (truncated)" : fullTrace;
    }

    private record FailureTransition(XxlJobAlertEvent.AlertType alertType, int totalFailures, Duration failureDuration) {
    }

    private record RecoveryTransition(int totalFailures, Duration failureDuration) {
    }

    /**
     * 单任务告警状态模型（基于 {@link ReentrantLock} 保证虚拟线程及高并发状态转移原子性）
     */
    private static class JobAlertState {
        private final ReentrantLock stateLock = new ReentrantLock();
        private boolean inFailureState;
        private int totalFailureCount;
        private int lastAlertCount;
        private long firstFailureTimestamp;
        private long lastAlertTimestamp;

        /**
         * 记录一次失败并在锁内原子计算本次告警决策（首发、阶段摘要或抑制）
         */
        public FailureTransition recordFailure(long now, int threshold, Duration suppressDuration) {
            stateLock.lock();
            try {
                totalFailureCount++;
                if (!inFailureState) {
                    inFailureState = true;
                    firstFailureTimestamp = now;
                    lastAlertTimestamp = now;
                    lastAlertCount = 0;
                    return new FailureTransition(XxlJobAlertEvent.AlertType.INITIAL_FAILURE, totalFailureCount, Duration.ZERO);
                }

                int diff = totalFailureCount - lastAlertCount;
                long elapsed = Math.max(0L, now - lastAlertTimestamp);
                long suppressMs = suppressDuration != null ? suppressDuration.toMillis() : Duration.ofMinutes(5).toMillis();

                if (diff >= threshold || elapsed >= suppressMs) {
                    lastAlertCount = totalFailureCount;
                    lastAlertTimestamp = now;
                    Duration totalDuration = Duration.ofMillis(Math.max(0L, now - firstFailureTimestamp));
                    return new FailureTransition(XxlJobAlertEvent.AlertType.FAILURE_SUMMARY, totalFailureCount, totalDuration);
                }
                return new FailureTransition(null, totalFailureCount, Duration.ZERO);
            } finally {
                if (stateLock.isHeldByCurrentThread()) {
                    stateLock.unlock();
                }
            }
        }

        /**
         * 原子读取并在处于故障态时消费并重置自愈恢复快照；健康态调用返回 null
         */
        public RecoveryTransition consumeRecovery(long now) {
            stateLock.lock();
            try {
                if (!inFailureState) {
                    return null;
                }
                int failures = totalFailureCount;
                Duration failureDuration = Duration.ofMillis(Math.max(0L, now - firstFailureTimestamp));
                inFailureState = false;
                totalFailureCount = 0;
                lastAlertCount = 0;
                firstFailureTimestamp = 0;
                lastAlertTimestamp = 0;
                return new RecoveryTransition(failures, failureDuration);
            } finally {
                if (stateLock.isHeldByCurrentThread()) {
                    stateLock.unlock();
                }
            }
        }
    }

}
