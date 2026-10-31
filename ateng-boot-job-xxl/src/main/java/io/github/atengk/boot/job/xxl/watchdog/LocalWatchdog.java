package io.github.atengk.boot.job.xxl.watchdog;

import com.xxl.job.core.context.XxlJobHelper;
import io.github.atengk.boot.job.xxl.event.JobTimeoutEvent;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * XXL-Job 本地硬超时看门狗引擎
 *
 * <p>基于独立守护时间轮（Netty {@link HashedWheelTimer} 与受管线程工厂）监控任务执行生命周期。
 * 当任务运行时间超出预设阈值时，确定性触发工作线程 {@link Thread#interrupt()} 强中断，
 * 广播发布 {@link JobTimeoutEvent} 并记录结构化超时日志，彻底防御僵尸线程积压与线程池耗尽。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class LocalWatchdog implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(LocalWatchdog.class);

    private final HashedWheelTimer timer;
    private final ApplicationEventPublisher eventPublisher;

    public LocalWatchdog(ApplicationEventPublisher eventPublisher) {
        this(eventPublisher, new CustomizableThreadFactory("ateng-job-watchdog-"));
    }

    public LocalWatchdog(ApplicationEventPublisher eventPublisher, ThreadFactory threadFactory) {
        this.eventPublisher = eventPublisher;
        ThreadFactory factory = (threadFactory != null) ? threadFactory : new CustomizableThreadFactory("ateng-job-watchdog-");
        // 刻度步长 50ms，512 个槽位的时间轮，具备极低 CPU 占用与精准超时检测
        this.timer = new HashedWheelTimer(factory, 50, TimeUnit.MILLISECONDS, 512);
    }

    /**
     * 开启超时看门狗监听
     *
     * @param workerThread 正在执行任务的工作线程
     * @param timeout      本地硬超时阈值
     * @param metadata     任务执行元数据
     * @return 监听会话句柄
     */
    public WatchdogSession startWatch(Thread workerThread, Duration timeout, JobExecutionMetadata metadata) {
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

            log.warn("XXL-Job 任务本地硬超时触发 [{}], 耗时: {}ms (阈值: {}ms), 正在向工作线程 [{}] 发起强中断...",
                    jobDesc, elapsed.toMillis(), timeout.toMillis(), workerThread.getName());

            // 1. 结构化记录超时日志至 XXL-Job 控制台
            try {
                XxlJobHelper.handleTimeout("任务执行本地硬超时 (阈值: " + timeout + ")");
                XxlJobHelper.log("<br><font color=\"red\">----------- [LOCAL-WATCHDOG] 任务执行本地硬超时 (耗时: " +
                        elapsed.toMillis() + "ms, 阈值: " + timeout.toMillis() + "ms)，工作线程 [" +
                        workerThread.getName() + "] 已被强中断！ -----------</font>");
            } catch (Throwable ex) {
                log.debug("记录 XXL-Job 超时控制台日志异常 (可能处于离线测试): {}", ex.getMessage());
            }

            // 2. 确定性强中断工作线程
            try {
                workerThread.interrupt();
            } catch (Throwable ex) {
                log.error("强中断任务工作线程 [{}] 发生次生异常", workerThread.getName(), ex);
            }

            // 3. 发布 Spring 领域超时事件
            if (eventPublisher != null && metadata != null) {
                try {
                    JobTimeoutEvent event = new JobTimeoutEvent(
                            this,
                            metadata.getJobId(),
                            metadata.getJobName(),
                            metadata.getLogId(),
                            timeout,
                            elapsed,
                            workerThread.getName(),
                            metadata.getTraceId(),
                            metadata.getTenantId()
                    );
                    eventPublisher.publishEvent(event);
                } catch (Throwable ex) {
                    log.error("发布 JobTimeoutEvent 超时事件异常", ex);
                }
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);

        session.setTimeoutHandle(timeoutHandle);
        return session;
    }

    @Override
    public void destroy() {
        if (timer != null) {
            timer.stop();
        }
    }

    /**
     * 看门狗监听会话
     */
    public static class WatchdogSession {

        public static final WatchdogSession NOOP = new WatchdogSession();

        private Timeout timeoutHandle;
        private final AtomicBoolean timedOut = new AtomicBoolean(false);

        void setTimeoutHandle(Timeout timeoutHandle) {
            this.timeoutHandle = timeoutHandle;
        }

        public void markTimedOut() {
            this.timedOut.set(true);
        }

        /**
         * 当前会话是否已发生超时
         */
        public boolean isTimedOut() {
            return timedOut.get();
        }

        /**
         * 取消看门狗监听（正常完成或异常退出时调用）
         */
        public void cancel() {
            if (timeoutHandle != null && !timeoutHandle.isCancelled()) {
                timeoutHandle.cancel();
            }
        }
    }

}
