package io.github.atengk.boot.observability.metrics;

import io.github.atengk.boot.observability.event.ThreadPoolRejectedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;

import java.util.Objects;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 线程池任务拒绝追踪装饰器
 * <p>
 * 透明包装原生 {@link RejectedExecutionHandler}，在触发拒绝策略时原子累加累计拒绝任务数并在 Prometheus 暴露度量指标。
 * 同时向 Spring 容器广播发布 {@link ThreadPoolRejectedEvent} 领域事件，随后安全委托给原始处理策略。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
public class TrackingRejectedExecutionHandler implements RejectedExecutionHandler, ApplicationEventPublisherAware {

    private final RejectedExecutionHandler delegate;
    private final AtomicLong rejectedCount = new AtomicLong(0);
    private volatile String poolName;
    private volatile ApplicationEventPublisher eventPublisher;

    /**
     * 基础构造器（默认 poolName="default"，无事件发布器）
     *
     * @param delegate 原始被委托的拒绝处理程序
     */
    public TrackingRejectedExecutionHandler(RejectedExecutionHandler delegate) {
        this(delegate, "default", null);
    }

    /**
     * 指定线程池名称构造器
     *
     * @param delegate 原始被委托的拒绝处理程序
     * @param poolName 线程池名称
     */
    public TrackingRejectedExecutionHandler(RejectedExecutionHandler delegate, String poolName) {
        this(delegate, poolName, null);
    }

    /**
     * 全属性构造器
     *
     * @param delegate       原始被委托的拒绝处理程序
     * @param poolName       线程池名称
     * @param eventPublisher Spring 事件发布器
     */
    public TrackingRejectedExecutionHandler(RejectedExecutionHandler delegate, String poolName, ApplicationEventPublisher eventPublisher) {
        this.delegate = Objects.requireNonNull(delegate, "RejectedExecutionHandler delegate must not be null");
        this.poolName = (poolName != null && !poolName.isBlank()) ? poolName : "default";
        this.eventPublisher = eventPublisher;
    }

    /**
     * 包装指定的 RejectedExecutionHandler，若已是 TrackingRejectedExecutionHandler 则直接返回防重复嵌套
     *
     * @param handler 待包装的处理程序
     * @return TrackingRejectedExecutionHandler 实例
     */
    public static TrackingRejectedExecutionHandler wrap(RejectedExecutionHandler handler) {
        return wrap(handler, null, null);
    }

    /**
     * 包装指定的 RejectedExecutionHandler，支持指定线程池名称
     *
     * @param handler  待包装的处理程序
     * @param poolName 线程池名称
     * @return TrackingRejectedExecutionHandler 实例
     */
    public static TrackingRejectedExecutionHandler wrap(RejectedExecutionHandler handler, String poolName) {
        return wrap(handler, poolName, null);
    }

    /**
     * 包装指定的 RejectedExecutionHandler，支持指定线程池名称与事件发布器
     *
     * @param handler   待包装的处理程序
     * @param poolName  线程池名称
     * @param publisher Spring 事件发布器
     * @return TrackingRejectedExecutionHandler 实例
     */
    public static TrackingRejectedExecutionHandler wrap(
            RejectedExecutionHandler handler,
            String poolName,
            ApplicationEventPublisher publisher) {
        if (handler instanceof TrackingRejectedExecutionHandler existing) {
            if (publisher != null && existing.getEventPublisher() == null) {
                existing.setApplicationEventPublisher(publisher);
            }
            if (poolName != null && !poolName.isBlank() && ("default".equals(existing.getPoolName()) || existing.getPoolName() == null)) {
                existing.setPoolName(poolName);
            }
            return existing;
        }
        return new TrackingRejectedExecutionHandler(handler, poolName, publisher);
    }

    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        long count = rejectedCount.incrementAndGet();
        if (log.isWarnEnabled()) {
            log.warn("ateng-boot: Task rejected by ThreadPoolExecutor [pool={}, poolSize={}, activeCount={}, queueSize={}, cumulativeRejectedCount={}]",
                    poolName, executor.getPoolSize(), executor.getActiveCount(),
                    executor.getQueue() != null ? executor.getQueue().size() : 0, count);
        }
        publishRejectedEvent(r, executor, count);
        delegate.rejectedExecution(r, executor);
    }

    /**
     * 广播发布线程池饱和拒绝领域事件
     *
     * @param r        被拒绝的任务
     * @param executor 触发拒绝的线程池
     * @param count    累计拒绝任务总数
     */
    private void publishRejectedEvent(Runnable r, ThreadPoolExecutor executor, long count) {
        ApplicationEventPublisher publisher = this.eventPublisher;
        if (publisher == null) {
            return;
        }
        try {
            int poolSize = executor.getPoolSize();
            int corePoolSize = executor.getCorePoolSize();
            int maxPoolSize = executor.getMaximumPoolSize();
            int activeCount = executor.getActiveCount();
            int queueSize = (executor.getQueue() != null) ? executor.getQueue().size() : 0;
            int remainingCapacity = (executor.getQueue() != null) ? executor.getQueue().remainingCapacity() : 0;
            String taskDescription = (r != null) ? r.toString() : null;

            ThreadPoolRejectedEvent event = new ThreadPoolRejectedEvent(
                    this,
                    this.poolName,
                    poolSize,
                    corePoolSize,
                    maxPoolSize,
                    activeCount,
                    queueSize,
                    remainingCapacity,
                    count,
                    taskDescription
            );
            publisher.publishEvent(event);
        } catch (Throwable ex) {
            log.warn("ateng-boot: Failed to publish ThreadPoolRejectedEvent for pool [{}]", poolName, ex);
        }
    }

    /**
     * 获取累计被拒绝的任务总数
     *
     * @return 拒绝任务计数
     */
    public long getRejectedCount() {
        return rejectedCount.get();
    }

    /**
     * 获取累计被拒绝的任务总数（语义化对齐）
     *
     * @return 拒绝任务计数
     */
    public long getCumulativeRejectedCount() {
        return rejectedCount.get();
    }

    /**
     * 获取底层被包装的原始 RejectedExecutionHandler
     *
     * @return 原始处理器
     */
    public RejectedExecutionHandler getDelegate() {
        return delegate;
    }

    /**
     * 获取当前关联的线程池名称
     *
     * @return 线程池名称
     */
    public String getPoolName() {
        return poolName;
    }

    /**
     * 设置线程池名称
     *
     * @param poolName 线程池名称
     */
    public void setPoolName(String poolName) {
        this.poolName = (poolName != null && !poolName.isBlank()) ? poolName : "default";
    }

    /**
     * 获取事件发布器
     *
     * @return 事件发布器
     */
    public ApplicationEventPublisher getEventPublisher() {
        return eventPublisher;
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }
}
