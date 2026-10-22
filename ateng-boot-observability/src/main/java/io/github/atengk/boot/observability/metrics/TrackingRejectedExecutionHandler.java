package io.github.atengk.boot.observability.metrics;

import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 线程池任务拒绝追踪装饰器
 * 透明包装原生 {@link RejectedExecutionHandler}，在触发拒绝策略时原子累加累计拒绝任务数并在 Prometheus 暴露度量指标，
 * 随后安全委托给原始处理策略
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
public class TrackingRejectedExecutionHandler implements RejectedExecutionHandler {

    private final RejectedExecutionHandler delegate;
    private final AtomicLong rejectedCount = new AtomicLong(0);

    public TrackingRejectedExecutionHandler(RejectedExecutionHandler delegate) {
        this.delegate = Objects.requireNonNull(delegate, "RejectedExecutionHandler delegate must not be null");
    }

    /**
     * 包装指定的 RejectedExecutionHandler，若已是 TrackingRejectedExecutionHandler 则直接返回防重复嵌套
     *
     * @param handler 待包装的处理程序
     * @return TrackingRejectedExecutionHandler 实例
     */
    public static TrackingRejectedExecutionHandler wrap(RejectedExecutionHandler handler) {
        if (handler instanceof TrackingRejectedExecutionHandler existing) {
            return existing;
        }
        return new TrackingRejectedExecutionHandler(handler);
    }

    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        long count = rejectedCount.incrementAndGet();
        if (log.isWarnEnabled()) {
            log.warn("ateng-boot: Task rejected by ThreadPoolExecutor [poolSize={}, activeCount={}, queueSize={}, cumulativeRejectedCount={}]",
                    executor.getPoolSize(), executor.getActiveCount(), executor.getQueue().size(), count);
        }
        delegate.rejectedExecution(r, executor);
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
     * 获取底层被包装的原始 RejectedExecutionHandler
     *
     * @return 原始处理器
     */
    public RejectedExecutionHandler getDelegate() {
        return delegate;
    }
}
