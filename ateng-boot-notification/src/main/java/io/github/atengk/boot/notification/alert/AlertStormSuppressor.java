package io.github.atengk.boot.notification.alert;

import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.notification.model.CardColor;
import io.github.atengk.boot.notification.model.CardMessage;
import io.github.atengk.boot.notification.properties.NotificationAlertProperties;
import io.github.atengk.boot.notification.template.NotificationTemplate;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 协同通知防告警风暴聚合抑制引擎
 * 基于 MD5 异常指纹实施“首发即时通报 + 窗口期原子计数静默拦截 + 周期末聚合通报”三阶抗洪算法
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
public class AlertStormSuppressor implements DisposableBean {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NotificationTemplate notificationTemplate;
    private final NotificationAlertProperties alertProperties;
    private final ScheduledExecutorService scheduler;
    private final boolean selfManagedScheduler;

    private final ConcurrentMap<String, SuppressRecord> activeWindows = new ConcurrentHashMap<>();

    public AlertStormSuppressor(NotificationTemplate notificationTemplate, NotificationAlertProperties alertProperties) {
        this(notificationTemplate, alertProperties, createDefaultScheduler(), true);
    }

    public AlertStormSuppressor(NotificationTemplate notificationTemplate,
                                NotificationAlertProperties alertProperties,
                                ScheduledExecutorService scheduler) {
        this(notificationTemplate, alertProperties, scheduler, false);
    }

    private AlertStormSuppressor(NotificationTemplate notificationTemplate,
                                NotificationAlertProperties alertProperties,
                                ScheduledExecutorService scheduler,
                                boolean selfManagedScheduler) {
        this.notificationTemplate = notificationTemplate;
        this.alertProperties = alertProperties != null ? alertProperties : new NotificationAlertProperties();
        this.scheduler = scheduler;
        this.selfManagedScheduler = selfManagedScheduler;
    }

    /**
     * 处理异常告警上下文（执行三阶防风暴抑制算法）
     *
     * @param alert 结构化告警上下文
     * @return true 若为首发并立即触发了紧急通报；false 若命中窗口期被静默拦截
     */
    public boolean processAlert(AlertContext alert) {
        if (alert == null) {
            return false;
        }

        String fingerprint = alert.getFingerprint();
        Duration window = (alert.getSuppressWindow() != null && !alert.getSuppressWindow().isNegative() && !alert.getSuppressWindow().isZero())
                ? alert.getSuppressWindow()
                : alertProperties.getSuppressWindow();
        if (window == null || window.isZero() || window.isNegative()) {
            window = Duration.ofSeconds(60);
        }

        SuppressRecord newRecord = new SuppressRecord(alert, window);
        SuppressRecord existing = activeWindows.putIfAbsent(fingerprint, newRecord);

        if (existing == null) {
            // 阶段一：首发即时透传
            if (log.isDebugEnabled()) {
                log.debug("Dispatching initial emergency alert [fingerprint={}, method={}]",
                        fingerprint, alert.getMethodSignature());
            }
            try {
                sendEmergencyAlert(alert);
            } catch (Throwable ex) {
                log.error("首发即时告警卡片推送失败 [fingerprint={}, channel={}]: {}",
                        fingerprint, alert.getChannel(), ex.getMessage(), ex);
            }

            // 调度周期末汇总通报任务
            ScheduledFuture<?> future = scheduler.schedule(
                    () -> onWindowExpired(fingerprint),
                    window.toMillis(),
                    TimeUnit.MILLISECONDS
            );
            newRecord.setScheduledFuture(future);
            return true;
        } else {
            // 阶段二：窗口期静默抗洪，累加原子计数器
            int suppressedCount = existing.incrementSuppressed();
            if (log.isDebugEnabled()) {
                log.debug("Suppressing duplicate alert [fingerprint={}, count={}]", fingerprint, suppressedCount);
            }
            return false;
        }
    }

    /**
     * 计算异常指纹无碰撞哈希
     * 算法：MD5(ExceptionClassName + ":" + RootCauseClassName + ":" + MethodSignature + ":" + LineNumber)
     *
     * @param method    触发异常的目标方法
     * @param throwable 抛出的异常对象
     * @return 32 位小写十六进制 MD5 摘要
     */
    public static String computeFingerprint(Method method, Throwable throwable) {
        if (throwable == null) {
            return "00000000000000000000000000000000";
        }
        String exceptionClassName = throwable.getClass().getName();
        Throwable rootCause = findRootCause(throwable);
        String rootCauseClassName = rootCause.getClass().getName();
        String methodSignature = (method != null)
                ? method.getDeclaringClass().getName() + "#" + method.getName()
                : "unknown";
        int lineNumber = findFirstBusinessLineNumber(throwable, method);

        String raw = exceptionClassName + ":" + rootCauseClassName + ":" + methodSignature + ":" + lineNumber;
        return DigestUtil.md5Hex(raw);
    }

    /**
     * 提取最底层根因异常
     *
     * @param throwable 抛出的异常
     * @return 根因异常
     */
    public static Throwable findRootCause(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    /**
     * 查找业务代码首发错误行号
     *
     * @param throwable 异常实例
     * @param method    触发方法
     * @return 错误行号，未检索到返回 0
     */
    public static int findFirstBusinessLineNumber(Throwable throwable, Method method) {
        if (throwable == null) {
            return 0;
        }
        String declaringClassName = (method != null) ? method.getDeclaringClass().getName() : null;
        for (StackTraceElement element : throwable.getStackTrace()) {
            if (declaringClassName != null && element.getClassName().equals(declaringClassName)) {
                return Math.max(0, element.getLineNumber());
            }
        }
        if (throwable.getStackTrace().length > 0) {
            return Math.max(0, throwable.getStackTrace()[0].getLineNumber());
        }
        return 0;
    }

    /**
     * 获取当前处于活跃抑制窗口中的指纹数量
     *
     * @return 活跃窗口数量
     */
    public int getActiveWindowsCount() {
        return activeWindows.size();
    }

    /**
     * 获取指定指纹对应的抑制记录（用于测试断言与状态检查）
     *
     * @param fingerprint 异常指纹
     * @return SuppressRecord 记录
     */
    public SuppressRecord getRecord(String fingerprint) {
        return activeWindows.get(fingerprint);
    }

    /**
     * 重置并清空所有抑制窗口与待执行调度任务
     */
    public void reset() {
        activeWindows.values().forEach(record -> {
            if (record.getScheduledFuture() != null) {
                record.getScheduledFuture().cancel(false);
            }
        });
        activeWindows.clear();
    }

    @Override
    public void destroy() {
        reset();
        if (selfManagedScheduler && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
    }

    private void onWindowExpired(String fingerprint) {
        SuppressRecord record = activeWindows.remove(fingerprint);
        if (record != null) {
            int count = record.getSuppressedCount();
            if (count > 0) {
                // 阶段三：周期末汇总通报
                if (log.isDebugEnabled()) {
                    log.debug("Dispatching storm aggregation summary [fingerprint={}, count={}]", fingerprint, count);
                }
                try {
                    sendSummaryAlert(record, count);
                } catch (Throwable ex) {
                    log.error("告警风暴周期末汇总通报推送失败 [fingerprint={}]: {}",
                            fingerprint, ex.getMessage(), ex);
                }
            }
        }
    }

    private void sendEmergencyAlert(AlertContext alert) {
        String channel = resolveAlertChannel(alert.getChannel());
        String content = buildEmergencyContent(alert);

        CardMessage card = CardMessage.of("[紧急告警] " + alert.getTitle(), content)
                .headerColor(CardColor.RED);

        notificationTemplate.send(channel, card);
    }

    private void sendSummaryAlert(SuppressRecord record, int count) {
        AlertContext alert = record.getAlert();
        String channel = resolveAlertChannel(alert.getChannel());
        String content = buildSummaryContent(record, count);

        CardMessage card = CardMessage.of("【告警汇总】" + alert.getTitle(), content)
                .headerColor(CardColor.ORANGE);

        notificationTemplate.send(channel, card);
    }

    private String resolveAlertChannel(String explicitChannel) {
        if (explicitChannel != null && !explicitChannel.isBlank()) {
            return explicitChannel.trim();
        }
        if (alertProperties.getDefaultChannel() != null && !alertProperties.getDefaultChannel().isBlank()) {
            return alertProperties.getDefaultChannel().trim();
        }
        return null;
    }

    private String buildEmergencyContent(AlertContext alert) {
        StringBuilder sb = new StringBuilder();
        String timeStr = (alert.getOccurrenceTime() != null)
                ? alert.getOccurrenceTime().format(TIME_FORMATTER)
                : "";

        sb.append("**发生时间**: ").append(timeStr).append("\n");
        sb.append("**链路跟踪**: `").append(alert.getTraceId() != null ? alert.getTraceId() : "N/A").append("`\n");
        sb.append("**操作人员**: ").append(alert.getUserId() != null ? alert.getUserId() : "anonymous").append("\n");
        sb.append("**触发方法**: `").append(alert.getMethodSignature()).append("`\n");
        sb.append("**异常类型**: `").append(alert.getExceptionClassName()).append("`\n");
        sb.append("**异常信息**: `").append(alert.getExceptionMessage() != null ? alert.getExceptionMessage() : "").append("`\n");

        if (alert.getDesensitizedArgs() != null && !alert.getDesensitizedArgs().isBlank()) {
            sb.append("\n**方法入参**:\n```json\n")
                    .append(alert.getDesensitizedArgs())
                    .append("\n```\n");
        }

        if (alert.getTopStackFrames() != null && !alert.getTopStackFrames().isBlank()) {
            sb.append("\n**核心调用栈**:\n```text\n")
                    .append(alert.getTopStackFrames())
                    .append("\n```");
        }

        return sb.toString();
    }

    private String buildSummaryContent(SuppressRecord record, int count) {
        AlertContext alert = record.getAlert();
        long windowSeconds = record.getWindowDuration().toSeconds();
        if (windowSeconds <= 0) {
            windowSeconds = Math.max(1, record.getWindowDuration().toMillis() / 1000);
        }

        return String.format(
                "在过去 **%d** 秒内，该异常在相同代码位置累计重复触发了 **%d** 次。\n\n" +
                        "- **异常指纹**: `%s`\n" +
                        "- **首发排查链路**: `%s`\n" +
                        "- **触发方法**: `%s`\n" +
                        "- **异常类型**: `%s`",
                windowSeconds,
                count,
                alert.getFingerprint(),
                alert.getTraceId() != null ? alert.getTraceId() : "N/A",
                alert.getMethodSignature(),
                alert.getExceptionClassName()
        );
    }

    private static ScheduledExecutorService createDefaultScheduler() {
        ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r, "alert-storm-suppressor");
            t.setDaemon(true);
            return t;
        };
        return Executors.newSingleThreadScheduledExecutor(threadFactory);
    }

    /**
     * 内部单指纹窗口抑制状态记录
     */
    @Getter
    public static class SuppressRecord {
        private final AlertContext alert;
        private final Duration windowDuration;
        private final AtomicInteger suppressedCount = new AtomicInteger(0);
        private volatile ScheduledFuture<?> scheduledFuture;

        SuppressRecord(AlertContext alert, Duration windowDuration) {
            this.alert = alert;
            this.windowDuration = windowDuration;
        }

        public int incrementSuppressed() {
            return suppressedCount.incrementAndGet();
        }

        public int getSuppressedCount() {
            return suppressedCount.get();
        }

        void setScheduledFuture(ScheduledFuture<?> future) {
            this.scheduledFuture = future;
        }
    }
}
