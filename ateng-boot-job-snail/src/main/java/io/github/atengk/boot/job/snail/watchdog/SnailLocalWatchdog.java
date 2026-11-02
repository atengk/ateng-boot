package io.github.atengk.boot.job.snail.watchdog;

import io.github.atengk.boot.core.concurrent.NamedThreadFactory;
import io.github.atengk.boot.job.snail.event.SnailJobTimeoutEvent;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SnailJob 本地硬超时看门狗引擎
 *
 * <p>基于 Netty {@link HashedWheelTimer} 时间轮监控任务执行生命周期。
 * 当任务运行时间超出预设阈值时，确定性触发工作线程 {@link Thread#interrupt()} 强中断，
 * 广播发布 {@link SnailJobTimeoutEvent} 并记录结构化超时日志，彻底防御僵尸线程积压与线程池耗尽。
 * 同时支持两级防御：当中断后工作线程在宽限期内顽固无响应时，异步触发全局 {@link JobTimeoutFallbackHandler} 紧急降级兜底。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class SnailLocalWatchdog implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SnailLocalWatchdog.class);

    private final HashedWheelTimer timer;
    private final ApplicationEventPublisher eventPublisher;
    private final Duration gracePeriod;
    private final List<JobTimeoutFallbackHandler> fallbackHandlers;
    private final Executor asyncExecutor;

    public SnailLocalWatchdog(ApplicationEventPublisher eventPublisher) {
        this(eventPublisher, null, Duration.ofSeconds(2), Collections.emptyList(), null);
    }

    public SnailLocalWatchdog(ApplicationEventPublisher eventPublisher, ThreadFactory threadFactory) {
        this(eventPublisher, threadFactory, Duration.ofSeconds(2), Collections.emptyList(), null);
    }

    public SnailLocalWatchdog(
            ApplicationEventPublisher eventPublisher,
            ThreadFactory threadFactory,
            Duration gracePeriod,
            List<JobTimeoutFallbackHandler> fallbackHandlers,
            Executor asyncExecutor) {
        this.eventPublisher = eventPublisher;
        this.gracePeriod = (gracePeriod != null && !gracePeriod.isNegative()) ? gracePeriod : Duration.ofSeconds(2);
        this.fallbackHandlers = (fallbackHandlers != null && !fallbackHandlers.isEmpty())
                ? Collections.unmodifiableList(new ArrayList<>(fallbackHandlers))
                : Collections.emptyList();
        this.asyncExecutor = asyncExecutor;
        ThreadFactory factory = (threadFactory != null) ? threadFactory : new NamedThreadFactory("ateng-snail-watchdog-", true);
        // 刻度步长 50ms，512 个槽位的时间轮，具备极低 CPU 占用与精准超时检测
        this.timer = new HashedWheelTimer(factory, 50, TimeUnit.MILLISECONDS, 512);
    }

    public Duration getGracePeriod() {
        return gracePeriod;
    }

    public List<JobTimeoutFallbackHandler> getFallbackHandlers() {
        return fallbackHandlers;
    }

    /**
     * 开启超时看门狗监听
     *
     * @param workerThread 正在执行任务的工作线程
     * @param timeout      本地硬超时阈值
     * @param metadata     任务执行上下文元数据
     * @return 监听会话句柄
     */
    public WatchdogSession startWatch(Thread workerThread, Duration timeout, SnailJobExecutionMetadata metadata) {
        Objects.requireNonNull(workerThread, "workerThread 不能为空");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return WatchdogSession.NOOP;
        }

        Instant startTime = Instant.now();
        WatchdogSession session = new WatchdogSession();

        Timeout timeoutHandle = timer.newTimeout(t -> {
            session.markTimedOut();
            Duration elapsed = Duration.between(startTime, Instant.now());
            String jobDesc = (metadata != null) ? (metadata.getJobName() + " (jobId: " + metadata.getJobId() + ")") : "UnknownJob";

            log.warn("SnailJob 任务本地硬超时触发 [{}], 耗时: {}ms (阈值: {}ms), 正在向工作线程 [{}] 发起强中断...",
                    jobDesc, elapsed.toMillis(), timeout.toMillis(), workerThread.getName());

            // 1. 确定性强中断工作线程
            try {
                workerThread.interrupt();
            } catch (Throwable ex) {
                log.error("强中断任务工作线程 [{}] 发生次生异常", workerThread.getName(), ex);
            }

            // 2. 发布 Spring 领域超时事件
            if (eventPublisher != null && metadata != null) {
                try {
                    SnailJobTimeoutEvent event = new SnailJobTimeoutEvent(
                            this,
                            metadata.getJobName(),
                            metadata.getJobId(),
                            metadata.getTaskBatchId(),
                            timeout,
                            elapsed,
                            workerThread.getName(),
                            metadata.getTraceId()
                    );
                    eventPublisher.publishEvent(event);
                } catch (Throwable ex) {
                    log.error("发布 SnailJobTimeoutEvent 领域事件异常", ex);
                }
            }

            // 3. 调度第二级紧急宽限期检查 (若工作线程顽固未终止，异步派发全局降级 SPI)
            if (gracePeriod != null && !gracePeriod.isZero() && !gracePeriod.isNegative()
                    && !fallbackHandlers.isEmpty()) {
                Timeout graceTimeout = timer.newTimeout(gt -> {
                    if (workerThread.isAlive() && !session.isCancelled()) {
                        log.error("SnailJob 任务工作线程 [{}] 在中断宽限期 ({}ms) 后仍顽固处于存活状态，触发第二级紧急异步全局降级补偿...",
                                workerThread.getName(), gracePeriod.toMillis());
                        dispatchEmergencyFallback(metadata, elapsed.plus(gracePeriod));
                    }
                }, gracePeriod.toMillis(), TimeUnit.MILLISECONDS);
                session.setGraceTimeoutHandle(graceTimeout);
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);

        session.setTimeoutHandle(timeoutHandle);
        return session;
    }

    private void dispatchEmergencyFallback(SnailJobExecutionMetadata metadata, Duration elapsed) {
        Runnable task = () -> {
            for (JobTimeoutFallbackHandler handler : fallbackHandlers) {
                try {
                    handler.onTimeout(metadata, elapsed);
                } catch (Throwable ex) {
                    log.error("紧急全局超时降级处理器 [{}] 执行异常", handler.getClass().getSimpleName(), ex);
                }
            }
        };
        if (asyncExecutor != null) {
            asyncExecutor.execute(task);
        } else {
            Thread emergencyThread = new Thread(task, "ateng-snail-watchdog-emergency");
            emergencyThread.setDaemon(true);
            emergencyThread.start();
        }
    }

    @Override
    public void destroy() {
        if (timer != null) {
            timer.stop();
        }
    }

    /**
     * 看门狗会话句柄
     */
    public static class WatchdogSession {

        public static final WatchdogSession NOOP = new WatchdogSession() {
            @Override
            public void cancel() {
            }

            @Override
            public boolean isTimedOut() {
                return false;
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };

        private volatile Timeout timeoutHandle;
        private volatile Timeout graceTimeoutHandle;
        private final AtomicBoolean timedOut = new AtomicBoolean(false);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        public void setTimeoutHandle(Timeout timeoutHandle) {
            this.timeoutHandle = timeoutHandle;
        }

        public void setGraceTimeoutHandle(Timeout graceTimeoutHandle) {
            this.graceTimeoutHandle = graceTimeoutHandle;
        }

        public void cancel() {
            cancelled.set(true);
            Timeout handle = this.timeoutHandle;
            if (handle != null && !handle.isExpired()) {
                handle.cancel();
            }
            Timeout grace = this.graceTimeoutHandle;
            if (grace != null && !grace.isExpired()) {
                grace.cancel();
            }
        }

        public void markTimedOut() {
            timedOut.set(true);
        }

        public boolean isTimedOut() {
            return timedOut.get();
        }

        public boolean isCancelled() {
            return cancelled.get();
        }
    }
}
