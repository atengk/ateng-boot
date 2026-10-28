package io.github.atengk.boot.web.shutdown;

import io.github.atengk.boot.web.properties.WebProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kubernetes 容器生命周期流量排空与优雅停机处理器
 * <p>
 * 核心能力：
 * 1. 翻转 Spring Boot 探针就绪状态至 {@link ReadinessState#REFUSING_TRAFFIC}；
 * 2. 受管阻塞静默等待可配置时长（{@code drain-timeout}），为上游 Ingress / 网关摘除端点并排空在途流量预留窗口；
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
        this.properties = (webProperties != null && webProperties.getShutdown() != null)
                ? webProperties.getShutdown()
                : new WebProperties.GracefulShutdownProperties();
    }

    public AtengGracefulShutdownHandler(WebProperties.GracefulShutdownProperties properties) {
        this.properties = properties != null ? properties : new WebProperties.GracefulShutdownProperties();
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.eventPublisher = applicationEventPublisher;
    }

    /**
     * 执行流量排空与探针状态变更
     *
     * @return 排空结果摘要
     */
    public DrainResult drain() {
        if (!properties.isEnabled()) {
            log.info("[Graceful Shutdown] Traffic drain handler is disabled via configuration.");
            return new DrainResult("DISABLED", false, "0s", "Traffic drain is disabled.");
        }

        // 原子抢占排空执行权，保障幂等防重入
        if (!drained.compareAndSet(false, true)) {
            log.info("[Graceful Shutdown] Traffic drain has already been executed or is currently in progress. Skipping duplicate drain.");
            return new DrainResult(
                    ReadinessState.REFUSING_TRAFFIC.name(),
                    false,
                    formatDuration(properties.getDrainTimeout()),
                    "Traffic drain already executed or in progress."
            );
        }

        // 1. 将 Spring Boot 就绪探针状态翻转为拒绝流量
        publishRefusingTraffic();

        // 2. 静默阻塞等待网关摘除路由与在途请求排空
        Duration timeout = properties.getDrainTimeout();
        long millis = (timeout != null && !timeout.isNegative()) ? timeout.toMillis() : 0L;
        if (millis > 0) {
            log.warn("[Graceful Shutdown] ReadinessState changed to REFUSING_TRAFFIC. Starting traffic drain sleep for {} ms (timeout: {})...",
                    millis, timeout);
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[Graceful Shutdown] Traffic drain sleep interrupted: {}", e.getMessage());
            }
        }

        log.info("[Graceful Shutdown] Traffic drain finished successfully.");
        return new DrainResult(
                ReadinessState.REFUSING_TRAFFIC.name(),
                true,
                formatDuration(timeout),
                "Traffic drain completed successfully."
        );
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
            String message
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("status", status);
            map.put("executed", executed);
            map.put("drainTimeout", drainTimeout);
            map.put("message", message);
            return map;
        }
    }
}
