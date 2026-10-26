package io.github.atengk.boot.core.model;

import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 平台与虚拟线程池运行时全景监控状态快照
 * 捕获核心/最大容量、活跃线程、排队深度、饱和度指标以及虚拟线程执行器特有参数，为运行时动态调优与健康诊断提供强类型数据模型
 *
 * @param corePoolSize           核心线程数（虚拟线程模式下为 0）
 * @param maxPoolSize            最大线程数（虚拟线程模式下若配置了并发限制则为限制值，无界时为 -1）
 * @param poolSize               当前线程池中存活的工作线程数
 * @param activeCount            正在执行任务的活跃工作线程数
 * @param queueSize              阻塞队列中正在排队等待调度的任务数（虚拟线程模式下为 0）
 * @param remainingQueueCapacity 阻塞队列当前剩余空闲容量（虚拟线程模式下为 0）
 * @param completedTaskCount     已完成执行的历史任务累计总数（虚拟线程模式下为 0）
 * @param isSaturated            线程池当前是否处于饱和状态（活跃任务达到并发限制或平台线程池队列占满）
 * @param virtual                当前执行器是否为虚拟线程执行器
 * @param concurrencyLimit       虚拟线程并发度限制阈值（-1 表示无界限制）
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
        boolean isSaturated,
        boolean virtual,
        int concurrencyLimit
) {

    /**
     * 向后兼容的 8 参数构造函数（默认视为平台线程池，无虚拟线程限制）
     *
     * @param corePoolSize           核心线程数
     * @param maxPoolSize            最大线程数
     * @param poolSize               当前存活线程数
     * @param activeCount            活跃线程数
     * @param queueSize              等待队列长度
     * @param remainingQueueCapacity 剩余队列容量
     * @param completedTaskCount     已完成任务数
     * @param isSaturated            是否处于饱和状态
     */
    public ThreadPoolStatus(
            int corePoolSize,
            int maxPoolSize,
            int poolSize,
            int activeCount,
            int queueSize,
            int remainingQueueCapacity,
            long completedTaskCount,
            boolean isSaturated
    ) {
        this(
                corePoolSize,
                maxPoolSize,
                poolSize,
                activeCount,
                queueSize,
                remainingQueueCapacity,
                completedTaskCount,
                isSaturated,
                false,
                -1
        );
    }

    /**
     * 是否为虚拟线程执行器（JavaBean 风格别名）
     *
     * @return 若为虚拟线程执行器返回 true，平台线程池返回 false
     */
    public boolean isVirtual() {
        return virtual;
    }

    /**
     * 获取并发限制阈值（JavaBean 风格别名）
     *
     * @return 并发限制数；-1 表示无界限制
     */
    public int getConcurrencyLimit() {
        return concurrencyLimit;
    }

    /**
     * 基于 JDK 原生 {@link ThreadPoolExecutor} 抓取实时指标快照
     *
     * @param executor JDK 原生线程池执行器
     * @return 线程池运行时状态快照
     * @throws NullPointerException 当 executor 为 null 时抛出
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
                isSaturated,
                false,
                -1
        );
    }

    /**
     * 基于 Spring {@link ThreadPoolTaskExecutor} 抓取实时指标快照
     *
     * @param taskExecutor Spring 受管线程池执行器
     * @return 线程池运行时状态快照
     * @throws NullPointerException 当 taskExecutor 为 null 时抛出
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
                    false,
                    false,
                    -1
            );
        }
        return of(underlyingExecutor);
    }

    /**
     * 基于 Spring {@link SimpleAsyncTaskExecutor} 抓取虚拟线程运行态度量快照
     *
     * @param executor Spring 异步任务执行器
     * @return 虚拟线程运行时状态快照
     * @throws NullPointerException 当 executor 为 null 时抛出
     */
    public static ThreadPoolStatus ofVirtual(SimpleAsyncTaskExecutor executor) {
        Objects.requireNonNull(executor, "executor 不能为空");
        return ofVirtual(executor, executor.getConcurrencyLimit(), 0);
    }

    /**
     * 基于 Spring {@link SimpleAsyncTaskExecutor} 抓取虚拟线程运行态度量快照
     *
     * @param executor         Spring 异步任务执行器
     * @param concurrencyLimit 并发限制阈值（小于 0 表示无界限制）
     * @param activeCount      当前活跃执行中的任务数
     * @return 虚拟线程运行时状态快照
     * @throws NullPointerException 当 executor 为 null 时抛出
     */
    public static ThreadPoolStatus ofVirtual(SimpleAsyncTaskExecutor executor, int concurrencyLimit, int activeCount) {
        Objects.requireNonNull(executor, "executor 不能为空");
        int limit = concurrencyLimit >= 0 ? concurrencyLimit : executor.getConcurrencyLimit();
        int maxPoolSize = limit > 0 ? limit : -1;
        boolean isSaturated = limit > 0 && activeCount >= limit;

        return new ThreadPoolStatus(
                0,
                maxPoolSize,
                activeCount,
                activeCount,
                0,
                0,
                0L,
                isSaturated,
                true,
                limit
        );
    }
}
