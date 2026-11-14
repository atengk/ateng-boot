package io.github.atengk.boot.web.shutdown;

import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.trace.TraceFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kubernetes 容器生命周期流量排空与优雅停机处理器
 * <p>
 * 核心能力：
 * 1. 翻转 Spring Boot 探针就绪状态至 {@link ReadinessState#REFUSING_TRAFFIC}；
 * 2. 两阶段自适应排空等待（{@code drain-timeout}、{@code quiet-period} 与 {@code poll-interval}）：先静默等待路由摘除传播，剩余窗口内按步长探测在途计数归零即提前退出；
 * 3. 基于 {@link AtomicBoolean} 实现状态机原子幂等防重入，避免并发调用重复阻塞；
 * 4. 实现 {@link SmartLifecycle} 作为 SIGTERM 停机信号兜底保护。
 *
 * @author Ateng
 * @since 2026-10-18
 */
@Slf4j
public class AtengGracefulShutdownHandler implements SmartLifecycle, ApplicationEventPublisherAware {

    /**
     * 优雅停机与流量排空配置属性
     */
    private final WebProperties.GracefulShutdownProperties properties;

    /**
     * 可选链路过滤器，用于感知当前在途 HTTP 请求数
     */
    private final TraceFilter traceFilter;

    /**
     * Spring 事件发布器（用于向容器广播探针状态变更事件）
     */
    private ApplicationEventPublisher eventPublisher;

    /**
     * 排空执行原子状态机（保证仅排空一次，并发幂等防重入）
     */
    private final AtomicBoolean drained = new AtomicBoolean(false);

    /**
     * SmartLifecycle 运行中状态标志
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AtengGracefulShutdownHandler(WebProperties webProperties) {
        this(webProperties, null);
    }

    public AtengGracefulShutdownHandler(WebProperties webProperties, TraceFilter traceFilter) {
        this.properties = (webProperties != null && webProperties.getShutdown() != null)
                ? webProperties.getShutdown()
                : new WebProperties.GracefulShutdownProperties();
        this.traceFilter = traceFilter;
    }

    public AtengGracefulShutdownHandler(WebProperties.GracefulShutdownProperties properties) {
        this(properties, null);
    }

    public AtengGracefulShutdownHandler(WebProperties.GracefulShutdownProperties properties, TraceFilter traceFilter) {
        this.properties = properties != null ? properties : new WebProperties.GracefulShutdownProperties();
        this.traceFilter = traceFilter;
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.eventPublisher = applicationEventPublisher;
    }

    /**
     * 获取当前在途 HTTP 请求数（未装配 TraceFilter 时返回 0）
     *
     * @return 当前活跃在途请求数
     */
    public long getInFlightRequestCount() {
        return traceFilter != null ? traceFilter.getActiveRequestCount() : 0L;
    }

    /**
     * 执行流量排空与探针状态变更
     *
     * @return 排空结果摘要
     */
    public DrainResult drain() {
        long inFlightAtStart = getInFlightRequestCount();
        if (!properties.isEnabled()) {
            log.info("[Graceful Shutdown] Traffic drain handler is disabled via configuration.");
            return new DrainResult("DISABLED", false, "0s", "Traffic drain is disabled.",
                    inFlightAtStart, getInFlightRequestCount());
        }

        // 原子抢占排空执行权，保障幂等防重入
        if (!drained.compareAndSet(false, true)) {
            log.info("[Graceful Shutdown] Traffic drain has already been executed or is currently in progress. Skipping duplicate drain.");
            return new DrainResult(
                    ReadinessState.REFUSING_TRAFFIC.name(),
                    false,
                    formatDuration(properties.getDrainTimeout()),
                    "Traffic drain already executed or in progress.",
                    inFlightAtStart,
                    getInFlightRequestCount()
            );
        }

        // 1. 将 Spring Boot 就绪探针状态翻转为拒绝流量
        publishRefusingTraffic();

        // 2. 两阶段自适应排空：先执行静默期（未显式配置 quietPeriod 时等价于全量 drainTimeout），剩余窗口内按固定间隔探测在途计数归零即提前退出
        Duration timeout = properties.getDrainTimeout();
        long totalMillis = toNonNegativeMillis(timeout);
        boolean completedNormally = totalMillis <= 0 || waitForAdaptiveDrain(timeout, totalMillis, inFlightAtStart);

        long inFlightRemaining = getInFlightRequestCount();
        if (completedNormally) {
            log.info("[Graceful Shutdown] Traffic drain finished (inFlightAtStart={}, inFlightRemaining={}).",
                    inFlightAtStart, inFlightRemaining);
        } else {
            log.warn("[Graceful Shutdown] Traffic drain exited early due to thread interruption (inFlightAtStart={}, inFlightRemaining={}).",
                    inFlightAtStart, inFlightRemaining);
        }
        return new DrainResult(
                ReadinessState.REFUSING_TRAFFIC.name(),
                true,
                formatDuration(timeout),
                "Traffic drain completed successfully.",
                inFlightAtStart,
                inFlightRemaining
        );
    }

    /**
     * 执行两阶段自适应流量排空等待。
     *
     * @param totalTimeout    总排空超时配置
     * @param totalMillis     总排空超时毫秒数
     * @param inFlightAtStart 停机开始时的在途请求数
     * @return true 表示正常完成等待或已排空，false 表示等待过程被线程中断
     */
    private boolean waitForAdaptiveDrain(Duration totalTimeout, long totalMillis, long inFlightAtStart) {
        long totalDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalMillis);
        Duration configuredQuiet = properties.getQuietPeriod();
        long quietMillis = (configuredQuiet == null)
                ? totalMillis
                : Math.min(toNonNegativeMillis(configuredQuiet), totalMillis);

        if (quietMillis > 0) {
            String quietDesc = configuredQuiet == null ? "default(drainTimeout)" : configuredQuiet.toString();
            log.warn("[Graceful Shutdown] ReadinessState changed to REFUSING_TRAFFIC (inFlightAtStart={}). Starting quiet period for {} ms (totalTimeout: {}, quietPeriod: {})...",
                    inFlightAtStart, quietMillis, totalTimeout, quietDesc);
            try {
                Thread.sleep(quietMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[Graceful Shutdown] Traffic drain quiet period interrupted: {}", e.getMessage());
                return false;
            }
        } else {
            log.warn("[Graceful Shutdown] ReadinessState changed to REFUSING_TRAFFIC (inFlightAtStart={}). Quiet period is 0 ms; entering adaptive in-flight polling (totalTimeout: {}).",
                    inFlightAtStart, totalTimeout);
        }

        if (getInFlightRequestCount() <= 0 || totalDeadlineNanos - System.nanoTime() <= 0) {
            return true;
        }

        long pollMillis = resolvePollIntervalMillis(properties.getPollInterval());
        long pollNanos = TimeUnit.MILLISECONDS.toNanos(pollMillis);

        while (getInFlightRequestCount() > 0) {
            long remainingNanos = totalDeadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(pollNanos, remainingNanos));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[Graceful Shutdown] Adaptive drain polling interrupted: {}", e.getMessage());
                return false;
            }
        }
        return true;
    }

    private static long toNonNegativeMillis(Duration duration) {
        return (duration != null && !duration.isNegative()) ? duration.toMillis() : 0L;
    }

    private static long resolvePollIntervalMillis(Duration pollInterval) {
        return (pollInterval != null && pollInterval.toMillis() > 0) ? pollInterval.toMillis() : 100L;
    }

    /**
     * 判断是否已执行过流量排空
     *
     * @return true 若已执行排空
     */
    public boolean isDrained() {
        return drained.get();
    }

    /**
     * 重置排空状态（供测试用例或容器重置使用）
     */
    void reset() {
        drained.set(false);
    }

    /**
     * 发布就绪探针拒绝流量事件
     */
    private void publishRefusingTraffic() {
        if (eventPublisher != null) {
            try {
                AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.REFUSING_TRAFFIC);
                log.info("[Graceful Shutdown] Published AvailabilityChangeEvent: ReadinessState -> REFUSING_TRAFFIC");
            } catch (Exception e) {
                log.error("[Graceful Shutdown] Failed to publish AvailabilityChangeEvent: {}", e.getMessage(), e);
            }
        } else {
            log.warn("[Graceful Shutdown] ApplicationEventPublisher is null, cannot publish ReadinessState.REFUSING_TRAFFIC");
        }
    }

    @Override
    public void start() {
        this.running.set(true);
    }

    @Override
    public void stop() {
        if (this.running.compareAndSet(true, false)) {
            if (properties.isEnabled() && properties.isSmartLifecycleEnabled()) {
                log.info("[Graceful Shutdown] SmartLifecycle triggered shutdown fallback...");
                drain();
            }
        }
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return this.running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        // 返回 Integer.MAX_VALUE，确保在容器停机阶段最先执行（先于 WebServer 停止），
        // 使得排空期间 WebServer 仍可正常处理在途请求与响应 preStop 探测
        return Integer.MAX_VALUE;
    }

    private String formatDuration(Duration duration) {
        return duration != null ? duration.toString() : "0s";
    }

    /**
     * 排空结果模型契约
     */
    public record DrainResult(
            String status,
            boolean executed,
            String drainTimeout,
            String message,
            long inFlightRequestsAtStart,
            long inFlightRequestsRemaining
    ) {
        /**
         * 向后兼容四参数构造器（在途请求数默认记为 0）
         */
        public DrainResult(String status, boolean executed, String drainTimeout, String message) {
            this(status, executed, drainTimeout, message, 0L, 0L);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("status", status);
            map.put("executed", executed);
            map.put("drainTimeout", drainTimeout);
            map.put("message", message);
            map.put("inFlightRequestsAtStart", inFlightRequestsAtStart);
            map.put("inFlightRequestsRemaining", inFlightRequestsRemaining);
            return map;
        }
    }
}
