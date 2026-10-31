package io.github.atengk.boot.job.snail.alert;

import io.github.atengk.boot.job.snail.event.SnailJobAlertEvent;
import io.github.atengk.boot.job.snail.event.SnailRetryDeadLetterEvent;
import io.github.atengk.boot.job.snail.properties.SnailJobProperties;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SnailJob 三阶告警防风暴聚合与自愈引擎
 *
 * <p>实现工业级告警治理状态机：
 * <ol>
 *     <li><b>首发告警 (First Failure)</b>：任务/重试首次失败时立即推送红色告警卡片并广播事件；</li>
 *     <li><b>风暴抑制与周期摘要 (Suppression & Summary)</b>：持续失败期间抑制中间重复骚扰，达到周期或阈值推送橙色聚合摘要卡片；</li>
 *     <li><b>自愈恢复通知 (Self-Healing Recovery)</b>：连续失败后首次成功执行推送绿色自愈卡片并重置健康状态；</li>
 *     <li><b>死信沉降告警 (Dead Letter Incident)</b>：重试任务达到上限进入死信后立即发布 {@link SnailRetryDeadLetterEvent} 并推送高危红色卡片。</li>
 * </ol>
 * 依托 {@link NotificationTemplate} 协同通知门面异步推送，无通知组件时平滑降级为 Spring 标准领域事件与结构化日志。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class SnailJobAlertAggregator {

    private static final Logger log = LoggerFactory.getLogger(SnailJobAlertAggregator.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SnailJobProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final NotificationTemplate notificationTemplate;

    private final ConcurrentMap<String, JobAlertState> stateMap = new ConcurrentHashMap<>();

    public SnailJobAlertAggregator(
            SnailJobProperties properties,
            ApplicationEventPublisher eventPublisher,
            NotificationTemplate notificationTemplate) {
        this.properties = properties != null ? properties : new SnailJobProperties();
        this.eventPublisher = eventPublisher;
        this.notificationTemplate = notificationTemplate;
    }

    public SnailJobAlertAggregator(
            SnailJobProperties properties,
            NotificationTemplate notificationTemplate,
            ApplicationEventPublisher eventPublisher) {
        this(properties, eventPublisher, notificationTemplate);
    }

    /**
     * 记录调度任务执行失败并触发三阶告警决策 (便捷重载)
     *
     * @param jobName   任务标识
     * @param throwable 抛出的异常信息
     */
    public void onJobFailure(String jobName, Throwable throwable) {
        onJobFailure(jobName, null, null, 0, throwable);
    }

    /**
     * 记录调度任务执行失败并触发三阶告警决策
     *
     * @param jobName    任务标识
     * @param param      执行入参
     * @param traceId    全链路追踪 traceId
     * @param durationMs 执行耗时 (毫秒)
     * @param throwable  抛出的异常信息
     */
    public void onJobFailure(String jobName, String param, String traceId, long durationMs, Throwable throwable) {
        if (!properties.getAlert().isEnabled()) {
            return;
        }

        String key = "job:" + (StringUtils.hasText(jobName) ? jobName : "defaultJob");
        handleFailure(key, jobName, param, traceId, durationMs, throwable);
    }

    /**
     * 记录调度任务成功执行并在曾经故障后触发自愈恢复通知 (便捷重载)
     *
     * @param jobName 任务标识
     */
    public void onJobSuccess(String jobName) {
        onJobSuccess(jobName, null, null, 0);
    }

    /**
     * 记录调度任务成功执行并在曾经故障后触发自愈恢复通知
     *
     * @param jobName    任务标识
     * @param param      执行入参
     * @param traceId    全链路追踪 traceId
     * @param durationMs 执行耗时 (毫秒)
     */
    public void onJobSuccess(String jobName, String param, String traceId, long durationMs) {
        if (!properties.getAlert().isEnabled()) {
            return;
        }

        String key = "job:" + (StringUtils.hasText(jobName) ? jobName : "defaultJob");
        handleSuccess(key, jobName, param, traceId, durationMs);
    }

    /**
     * 记录重试场景执行失败并触发三阶告警决策 (便捷重载)
     *
     * @param scene     重试场景
     * @param throwable 抛出的异常信息
     */
    public void onRetryFailure(String scene, Throwable throwable) {
        onRetryFailure(scene, null, null, 0, throwable);
    }

    /**
     * 记录重试场景执行失败并触发三阶告警决策
     *
     * @param scene      重试场景
     * @param param      执行入参
     * @param traceId    全链路追踪 traceId
     * @param durationMs 执行耗时 (毫秒)
     * @param throwable  抛出的异常信息
     */
    public void onRetryFailure(String scene, String param, String traceId, long durationMs, Throwable throwable) {
        if (!properties.getAlert().isEnabled()) {
            return;
        }

        String key = "retry:" + (StringUtils.hasText(scene) ? scene : "defaultScene");
        handleFailure(key, scene, param, traceId, durationMs, throwable);
    }

    /**
     * 记录重试场景成功执行并在曾经故障后触发自愈恢复通知 (便捷重载)
     *
     * @param scene 重试场景
     */
    public void onRetrySuccess(String scene) {
        onRetrySuccess(scene, null, null, 0);
    }

    /**
     * 记录重试场景成功执行并在曾经故障后触发自愈恢复通知
     *
     * @param scene      重试场景
     * @param param      执行入参
     * @param traceId    全链路追踪 traceId
     * @param durationMs 执行耗时 (毫秒)
     */
    public void onRetrySuccess(String scene, String param, String traceId, long durationMs) {
        if (!properties.getAlert().isEnabled()) {
            return;
        }

        String key = "retry:" + (StringUtils.hasText(scene) ? scene : "defaultScene");
        handleSuccess(key, scene, param, traceId, durationMs);
    }

    /**
     * 处理任务/重试失败逻辑
     */
    private void handleFailure(String stateKey, String name, String param, String traceId, long durationMs, Throwable throwable) {
        JobAlertState state = stateMap.computeIfAbsent(stateKey, k -> new JobAlertState());

        long now = System.currentTimeMillis();
        boolean isFirstFailure = state.recordFailure(now);

        if (isFirstFailure) {
            // 阶段一：首发失败立即推送
            dispatchAlert(SnailJobAlertEvent.AlertType.INITIAL_FAILURE, name, param, traceId, durationMs, 1, Duration.ZERO, throwable);
        } else {
            // 阶段二：检查是否达到周期摘要或数量阈值
            Duration suppressDuration = properties.getAlert().getSuppressDuration() != null
                    ? properties.getAlert().getSuppressDuration()
                    : Duration.ofMinutes(5);
            int threshold = properties.getAlert().getSuppressThreshold() > 0 ? properties.getAlert().getSuppressThreshold() : 3;

            if (state.shouldTriggerSummary(now, threshold, suppressDuration)) {
                int totalCount = state.getTotalFailures();
                Duration totalFailureDuration = Duration.ofMillis(now - state.firstFailureTimestamp);
                dispatchAlert(SnailJobAlertEvent.AlertType.FAILURE_SUMMARY, name, param, traceId, durationMs, totalCount, totalFailureDuration, throwable);
            } else {
                log.debug("SnailJob 目标 [{}] 持续失败中，已平滑抑制第 {} 次重复告警", name, state.getTotalFailures());
            }
        }
    }

    /**
     * 处理任务/重试自愈恢复逻辑
     */
    private void handleSuccess(String stateKey, String name, String param, String traceId, long durationMs) {
        JobAlertState state = stateMap.get(stateKey);
        if (state == null || !state.inFailureState.get()) {
            return;
        }

        // 阶段三：故障自愈恢复触发
        long now = System.currentTimeMillis();
        int totalFailures = state.getTotalFailures();
        Duration totalFailureDuration = Duration.ofMillis(now - state.firstFailureTimestamp);

        // 重置状态
        state.reset();

        dispatchAlert(SnailJobAlertEvent.AlertType.RECOVERY, name, param, traceId, durationMs, totalFailures, totalFailureDuration, null);
    }

    /**
     * 响应重试任务达到最大次数沉降为死信事件
     *
     * @param scene              重试场景
     * @param idempotentId       幂等标识 (可为 null)
     * @param bizNo              业务单号 (可为 null)
     * @param executorClassName  执行器类名
     * @param argsStr            执行入参 (可为 null)
     * @param retryCount         累计重试次数
     * @param throwable          异常信息
     * @param traceId            链路追踪 traceId
     */
    public void onRetryDeadLetter(
            String scene,
            String idempotentId,
            String bizNo,
            String executorClassName,
            String argsStr,
            int retryCount,
            Throwable throwable,
            String traceId) {

        // 1. 广播 Spring 领域事件
        if (eventPublisher != null) {
            try {
                eventPublisher.publishEvent(new SnailRetryDeadLetterEvent(
                        this, scene, idempotentId, bizNo, executorClassName, argsStr, retryCount, throwable, traceId
                ));
            } catch (Exception e) {
                log.error("广播 SnailRetryDeadLetterEvent 事件发生异常", e);
            }
        }

        // 2. 结构化日志记录
        log.error("🚨 [SnailJob 重试死信告警] 重试任务已沉降为死信: scene={}, bizNo={}, idempotentId={}, executor={}, retries={}, traceId={}, error={}",
                scene, bizNo, idempotentId, executorClassName, retryCount, traceId, throwable != null ? throwable.getMessage() : "unknown");

        // 3. 异步推送协同机器人高危红色卡片
        if (notificationTemplate != null) {
            try {
                CardMessage card = buildDeadLetterCardMessage(scene, idempotentId, bizNo, executorClassName, argsStr, retryCount, throwable, traceId);
                notificationTemplate.sendAsync(card);
            } catch (Throwable t) {
                log.warn("通过 NotificationTemplate 发送 SnailJob 重试死信卡片失败，已降级忽略: {}", t.getMessage());
            }
        }
    }

    private void dispatchAlert(
            SnailJobAlertEvent.AlertType alertType,
            String jobName,
            String param,
            String traceId,
            long durationMs,
            int failureCount,
            Duration failureDuration,
            Throwable throwable) {

        // 1. 发布 Spring 标准领域事件
        if (eventPublisher != null) {
            try {
                eventPublisher.publishEvent(new SnailJobAlertEvent(
                        this, alertType, jobName, param, traceId, durationMs, failureCount, failureDuration, throwable
                ));
            } catch (Exception e) {
                log.error("广播 SnailJobAlertEvent 事件发生异常", e);
            }
        }

        // 2. 结构化日志记录
        switch (alertType) {
            case INITIAL_FAILURE -> log.error("🚨 [SnailJob 首发告警] 任务执行失败: jobName={}, traceId={}, cost={}ms, error={}",
                    jobName, traceId, durationMs, throwable != null ? throwable.getMessage() : "unknown");
            case FAILURE_SUMMARY -> log.warn("⚠️ [SnailJob 聚合摘要] 任务持续失败: jobName={}, traceId={}, 累计失败={}次, 故障持续={}秒, 最新异常={}",
                    jobName, traceId, failureCount, failureDuration.toSeconds(), throwable != null ? throwable.getMessage() : "unknown");
            case RECOVERY -> log.info("✅ [SnailJob 自愈恢复] 任务故障已恢复正常: jobName={}, traceId={}, 曾累计失败={}次, 故障历时={}秒",
                    jobName, traceId, failureCount, failureDuration.toSeconds());
        }

        // 3. 异步推送协同机器人富文本卡片 (若引入了 ateng-boot-notification)
        if (notificationTemplate != null) {
            try {
                CardMessage card = buildCardMessage(alertType, jobName, param, traceId, durationMs, failureCount, failureDuration, throwable);
                notificationTemplate.sendAsync(card);
            } catch (Throwable t) {
                log.warn("通过 NotificationTemplate 发送 SnailJob 告警卡片失败，已降级忽略: {}", t.getMessage());
            }
        }
    }

    private CardMessage buildCardMessage(
            SnailJobAlertEvent.AlertType alertType,
            String jobName,
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
                content.append("**任务标识**: `").append(jobName).append("`\n\n");
                content.append("**链路追踪**: `").append(traceId != null ? traceId : "-").append("`\n\n");
                content.append("**调度入参**: `").append(param != null ? param : "-").append("`\n\n");
                content.append("**执行耗时**: `").append(durationMs).append(" ms`\n\n");
                content.append("**发生时间**: ").append(timeStr).append("\n\n");
                content.append("**异常堆栈**:\n```text\n")
                        .append(extractStackTraceSnippet(throwable))
                        .append("\n```");
                return CardMessage.of("🚨 [SnailJob 任务执行异常]", content.toString())
                        .headerColor(CardColor.RED);
            }
            case FAILURE_SUMMARY -> {
                content.append("**任务标识**: `").append(jobName).append("`\n\n");
                content.append("**累计失败**: `").append(failureCount).append(" 次`\n\n");
                content.append("**故障持续**: `").append(failureDuration.toSeconds()).append(" 秒`\n\n");
                content.append("**链路追踪**: `").append(traceId != null ? traceId : "-").append("`\n\n");
                content.append("**摘要时间**: ").append(timeStr).append("\n\n");
                content.append("**最新异常**:\n```text\n")
                        .append(extractStackTraceSnippet(throwable))
                        .append("\n```");
                return CardMessage.of("⚠️ [SnailJob 持续失败聚合摘要]", content.toString())
                        .headerColor(CardColor.ORANGE);
            }
            case RECOVERY -> {
                content.append("**任务标识**: `").append(jobName).append("`\n\n");
                content.append("**运行状态**: `已恢复正常执行`\n\n");
                content.append("**期间累计失败**: `").append(failureCount).append(" 次`\n\n");
                content.append("**故障总历时**: `").append(failureDuration.toSeconds()).append(" 秒`\n\n");
                content.append("**恢复时间**: ").append(timeStr);
                return CardMessage.of("✅ [SnailJob 任务故障自愈恢复]", content.toString())
                        .headerColor(CardColor.GREEN);
            }
            default -> throw new IllegalArgumentException("Unknown AlertType: " + alertType);
        }
    }

    private CardMessage buildDeadLetterCardMessage(
            String scene,
            String idempotentId,
            String bizNo,
            String executorClassName,
            String argsStr,
            int retryCount,
            Throwable throwable,
            String traceId) {

        String timeStr = LocalDateTime.now().format(TIME_FORMATTER);
        StringBuilder content = new StringBuilder();
        content.append("**重试场景**: `").append(scene).append("`\n\n");
        content.append("**业务单号**: `").append(bizNo != null ? bizNo : "-").append("`\n\n");
        content.append("**幂等标识**: `").append(idempotentId != null ? idempotentId : "-").append("`\n\n");
        content.append("**执行器类**: `").append(executorClassName != null ? executorClassName : "-").append("`\n\n");
        content.append("**累计重试**: `").append(retryCount).append(" 次 (已达上限)`\n\n");
        content.append("**链路追踪**: `").append(traceId != null ? traceId : "-").append("`\n\n");
        content.append("**沉降时间**: ").append(timeStr).append("\n\n");
        content.append("**入参载荷**: `").append(argsStr != null ? argsStr : "-").append("`\n\n");
        content.append("**终止异常**:\n```text\n")
                .append(extractStackTraceSnippet(throwable))
                .append("\n```");

        return CardMessage.of("🚨 [SnailJob 重试死信沉降告警]", content.toString())
                .headerColor(CardColor.RED);
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

    /**
     * 单任务/场景告警状态模型
     */
    static class JobAlertState {
        private final AtomicBoolean inFailureState = new AtomicBoolean(false);
        private final AtomicInteger totalFailureCount = new AtomicInteger(0);
        private final AtomicInteger lastAlertCount = new AtomicInteger(0);
        private volatile long firstFailureTimestamp;
        private volatile long lastAlertTimestamp;

        /**
         * 记录一次失败
         *
         * @param timestamp 发生时间戳
         * @return 是否为首发失败
         */
        public boolean recordFailure(long timestamp) {
            int current = totalFailureCount.incrementAndGet();
            if (current == 1 || inFailureState.compareAndSet(false, true)) {
                this.firstFailureTimestamp = timestamp;
                this.lastAlertTimestamp = timestamp;
                this.lastAlertCount.set(0);
                this.inFailureState.set(true);
                return true;
            }
            return false;
        }

        /**
         * 获取故障期间累计失败总次数
         */
        public int getTotalFailures() {
            return totalFailureCount.get();
        }

        /**
         * 判定并更新阶段摘要触发决策
         *
         * @param now              当前时间戳
         * @param threshold        连续失败步长阈值
         * @param suppressDuration 抑制周期时限
         * @return 是否触发摘要
         */
        public synchronized boolean shouldTriggerSummary(long now, int threshold, Duration suppressDuration) {
            int current = totalFailureCount.get();
            int last = lastAlertCount.get();
            int diff = current - last;
            long elapsed = now - lastAlertTimestamp;
            long suppressMs = suppressDuration != null ? suppressDuration.toMillis() : Duration.ofMinutes(5).toMillis();

            if (diff >= threshold || elapsed >= suppressMs) {
                this.lastAlertCount.set(current);
                this.lastAlertTimestamp = now;
                return true;
            }
            return false;
        }

        public void reset() {
            this.inFailureState.set(false);
            this.totalFailureCount.set(0);
            this.lastAlertCount.set(0);
            this.firstFailureTimestamp = 0;
            this.lastAlertTimestamp = 0;
        }
    }
}
