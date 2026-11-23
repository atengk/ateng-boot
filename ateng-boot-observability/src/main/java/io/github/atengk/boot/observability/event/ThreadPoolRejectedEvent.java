package io.github.atengk.boot.observability.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 受管线程池任务饱和拒绝领域事件
 * <p>
 * 当受管线程池（{@code ThreadPoolTaskExecutor} 或 {@code ThreadPoolExecutor}）工作队列打满并触发拒绝策略时发布，
 * 携带发生饱和拒绝的线程池名称、当前工作线程数、核心与最大容量、活跃线程数、队列积压深度及累计拒绝计数等瞬态快照。
 * 运维告警或业务组件可通过监听此事件实施毫秒级饱和告警与弹性调度联动。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Getter
public class ThreadPoolRejectedEvent extends ApplicationEvent {

    /**
     * 触发拒绝的线程池 Bean 名称或标识
     */
    private final String poolName;

    /**
     * 当前工作线程池中的线程总数
     */
    private final int poolSize;

    /**
     * 核心线程数
     */
    private final int corePoolSize;

    /**
     * 最大允许线程数
     */
    private final int maxPoolSize;

    /**
     * 当前正在执行任务的活跃线程数
     */
    private final int activeCount;

    /**
     * 当前工作队列中排队积压的任务数
     */
    private final int queueSize;

    /**
     * 工作队列剩余可用容量
     */
    private final int remainingQueueCapacity;

    /**
     * 该线程池自启动以来累计被拒绝的任务总数
     */
    private final long cumulativeRejectedCount;

    /**
     * 被拒绝任务的描述信息（如任务对象的 toString）
     */
    private final String taskDescription;

    /**
     * 全属性构造器
     *
     * @param source                  事件源（通常为 TrackingRejectedExecutionHandler 实例）
     * @param poolName                线程池名称
     * @param poolSize                当前线程池工作线程数
     * @param corePoolSize            核心线程数
     * @param maxPoolSize             最大允许线程数
     * @param activeCount             活跃工作线程数
     * @param queueSize               队列当前排队任务数
     * @param remainingQueueCapacity  队列剩余可用容量
     * @param cumulativeRejectedCount 累计被拒绝任务总数
     * @param taskDescription         被拒绝任务描述
     */
    public ThreadPoolRejectedEvent(
            Object source,
            String poolName,
            int poolSize,
            int corePoolSize,
            int maxPoolSize,
            int activeCount,
            int queueSize,
            int remainingQueueCapacity,
            long cumulativeRejectedCount,
            String taskDescription) {
        super(source);
        this.poolName = poolName;
        this.poolSize = poolSize;
        this.corePoolSize = corePoolSize;
        this.maxPoolSize = maxPoolSize;
        this.activeCount = activeCount;
        this.queueSize = queueSize;
        this.remainingQueueCapacity = remainingQueueCapacity;
        this.cumulativeRejectedCount = cumulativeRejectedCount;
        this.taskDescription = taskDescription;
    }

    /**
     * 便捷构造器（核心指标快照）
     *
     * @param source                  事件源
     * @param poolName                线程池名称
     * @param poolSize                当前工作线程数
     * @param activeCount             活跃工作线程数
     * @param queueSize               队列当前排队任务数
     * @param cumulativeRejectedCount 累计被拒绝任务总数
     */
    public ThreadPoolRejectedEvent(
            Object source,
            String poolName,
            int poolSize,
            int activeCount,
            int queueSize,
            long cumulativeRejectedCount) {
        this(source, poolName, poolSize, -1, -1, activeCount, queueSize, -1, cumulativeRejectedCount, null);
    }

    @Override
    public String toString() {
        return "ThreadPoolRejectedEvent{" +
                "poolName='" + poolName + '\'' +
                ", poolSize=" + poolSize +
                ", corePoolSize=" + corePoolSize +
                ", maxPoolSize=" + maxPoolSize +
                ", activeCount=" + activeCount +
                ", queueSize=" + queueSize +
                ", remainingQueueCapacity=" + remainingQueueCapacity +
                ", cumulativeRejectedCount=" + cumulativeRejectedCount +
                ", taskDescription='" + taskDescription + '\'' +
                ", timestamp=" + getTimestamp() +
                '}';
    }
}
