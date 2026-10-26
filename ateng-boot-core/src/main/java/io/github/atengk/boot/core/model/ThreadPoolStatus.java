package io.github.atengk.boot.core.model;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 平台线程池运行时全景监控状态快照
 * 捕获核心/最大容量、活跃线程、排队深度与饱和度指标，为运行时动态调优与健康诊断提供强类型数据模型
 *
 * @param corePoolSize           核心线程数
 * @param maxPoolSize            最大线程数
 * @param poolSize               当前线程池中存活的工作线程数
 * @param activeCount            正在执行任务的活跃工作线程数
 * @param queueSize              阻塞队列中正在排队等待调度的任务数
 * @param remainingQueueCapacity 阻塞队列当前剩余空闲容量
 * @param completedTaskCount     已完成执行的历史任务累计总数
 * @param isSaturated            线程池当前是否处于饱和状态（活跃线程达到最大容量且队列无可用空闲缓冲）
 * @author Ateng
 * @since 2026-10-26
 */
public record ThreadPoolStatus(
        int corePoolSize,
        int maxPoolSize,
        int poolSize,
        int activeCount,
        int queueSize,
        int remainingQueueCapacity,
        long completedTaskCount,
        boolean isSaturated
) {

    /**
     * 基于 JDK 原生 {@link ThreadPoolExecutor} 抓取实时指标快照
     *
     * @param executor JDK 原生线程池执行器
     * @return 线程池运行时状态快照
     * @throws IllegalArgumentException 当 executor 为 null 时抛出
     */
    public static ThreadPoolStatus of(ThreadPoolExecutor executor) {
        Objects.requireNonNull(executor, "executor 不能为空");

        int corePoolSize = executor.getCorePoolSize();
        int maxPoolSize = executor.getMaximumPoolSize();
        int poolSize = executor.getPoolSize();
        int activeCount = executor.getActiveCount();
        BlockingQueue<Runnable> queue = executor.getQueue();
        int queueSize = queue != null ? queue.size() : 0;
        int remainingQueueCapacity = queue != null ? queue.remainingCapacity() : 0;
        long completedTaskCount = executor.getCompletedTaskCount();

        // 当活跃线程数达到最大容量且等待队列已无剩余可用容量时，判定为处于饱和状态
        boolean isSaturated = (activeCount >= maxPoolSize || poolSize >= maxPoolSize) && remainingQueueCapacity == 0;

        return new ThreadPoolStatus(
                corePoolSize,
                maxPoolSize,
                poolSize,
                activeCount,
                queueSize,
                remainingQueueCapacity,
                completedTaskCount,
                isSaturated
        );
    }

    /**
     * 基于 Spring {@link ThreadPoolTaskExecutor} 抓取实时指标快照
     *
     * @param taskExecutor Spring 受管线程池执行器
     * @return 线程池运行时状态快照
     * @throws IllegalArgumentException 当 taskExecutor 为 null 时抛出
     */
    public static ThreadPoolStatus of(ThreadPoolTaskExecutor taskExecutor) {
        Objects.requireNonNull(taskExecutor, "taskExecutor 不能为空");

        ThreadPoolExecutor underlyingExecutor;
        try {
            underlyingExecutor = taskExecutor.getThreadPoolExecutor();
        } catch (IllegalStateException e) {
            // 防御未调用 initialize() 的轻量脱机场景，返回配置层保底指标
            return new ThreadPoolStatus(
                    taskExecutor.getCorePoolSize(),
                    taskExecutor.getMaxPoolSize(),
                    0,
                    0,
                    0,
                    taskExecutor.getQueueCapacity(),
                    0L,
                    false
            );
        }
        return of(underlyingExecutor);
    }
}
