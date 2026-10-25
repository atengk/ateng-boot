package io.github.atengk.boot.job.local.lifecycle;

import io.github.atengk.boot.job.local.properties.JobLocalProperties;
import io.github.atengk.boot.job.local.registry.LocalJobRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单机定时任务生命周期编排与优雅停机管理器
 * <p>
 * 实现 Spring {@link SmartLifecycle} 规范，赋予最高优先级阶段（{@code Integer.MAX_VALUE - 100}），
 * 确保在 Web 容器与应用容器全部依赖就绪后才开始激活调度；应用停机时执行确定性排空等待，杜绝任务暴力截断。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class LocalJobLifecycleManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(LocalJobLifecycleManager.class);

    /**
     * 极高生命周期 Phase，晚于 Web 容器与普通组件启动，早于数据源与基础设施停机
     */
    public static final int LIFECYCLE_PHASE = Integer.MAX_VALUE - 100;

    private final LocalJobRegistry registry;
    private final JobLocalProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public LocalJobLifecycleManager(LocalJobRegistry registry, JobLocalProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    @Override
    public int getPhase() {
        return LIFECYCLE_PHASE;
    }

    @Override
    public boolean isAutoStartup() {
        return properties != null && properties.isEnabled();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Starting LocalJobLifecycleManager (phase: {}), activating scheduled local jobs...", getPhase());
            if (registry != null) {
                registry.startAll();
            }
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            Duration awaitPeriod = properties != null ? properties.getShutdownAwaitTerminationPeriod() : Duration.ofSeconds(30);
            log.info("Stopping LocalJobLifecycleManager, awaiting active jobs to complete (await period: {})...", awaitPeriod);
            if (registry != null) {
                registry.stopAll(awaitPeriod);
            }
        }
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            if (callback != null) {
                callback.run();
            }
        }
    }
}
