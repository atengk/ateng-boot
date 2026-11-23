package io.github.atengk.boot.job.snail.drain;

import com.aizuda.snailjob.client.common.rpc.client.grpc.GrpcChannel;
import com.aizuda.snailjob.client.job.core.cache.ThreadPoolCache;
import io.github.atengk.boot.job.snail.properties.SnailJobProperties;
import io.grpc.ManagedChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SnailJob 执行器三阶平滑停机排空状态机
 *
 * <p>响应 SIGTERM / Spring 容器销毁事件，按序执行三阶停机排空协议：
 * <ol>
 *     <li><b>阶段一：平滑离线标记 (Offline Mark)</b>：标记 {@code draining = true}，切面立即拦截并拒绝新调度与新重试任务接入；</li>
 *     <li><b>阶段二：在途任务排空 (In-Flight Drain)</b>：轮询等待在途调度与重试任务在 {@code drainTimeout} 阈值内平滑处理完毕；</li>
 *     <li><b>阶段三：物理连接销毁 (Resource Teardown)</b>：主动优雅关闭 Netty / gRPC 长连接通道，释放本地底层系统资源。</li>
 * </ol></p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class GracefulDrainStateMachine implements SmartLifecycle, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GracefulDrainStateMachine.class);

    private final SnailJobProperties properties;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean draining = new AtomicBoolean(false);

    private final AtomicInteger inFlightJobCount = new AtomicInteger(0);
    private final AtomicInteger inFlightRetryCount = new AtomicInteger(0);

    public GracefulDrainStateMachine(SnailJobProperties properties) {
        this.properties = properties != null ? properties : new SnailJobProperties();
    }

    /**
     * 判断当前是否已处于停机排空状态
     *
     * @return 若处于排空阶段返回 true
     */
    public boolean isDraining() {
        return draining.get();
    }

    /**
     * 显式设置停机排空状态 (主要用于测试或编程式受管触发)
     *
     * @param draining 是否排空中
     */
    public void setDraining(boolean draining) {
        this.draining.set(draining);
    }

    /**
     * 递增在途调度任务计数
     *
     * @return 递增后的计数值
     */
    public int incrementInFlightJob() {
        return inFlightJobCount.incrementAndGet();
    }

    /**
     * 递减在途调度任务计数
     *
     * @return 递减后的计数值
     */
    public int decrementInFlightJob() {
        return inFlightJobCount.decrementAndGet();
    }

    /**
     * 获取当前在途调度任务数
     *
     * @return 在途调度任务计数
     */
    public int getInFlightJobCount() {
        return inFlightJobCount.get();
    }

    /**
     * 递增在途重试任务计数
     *
     * @return 递增后的计数值
     */
    public int incrementInFlightRetry() {
        return inFlightRetryCount.incrementAndGet();
    }

    /**
     * 递减在途重试任务计数
     *
     * @return 递减后的计数值
     */
    public int decrementInFlightRetry() {
        return inFlightRetryCount.decrementAndGet();
    }

    /**
     * 获取当前在途重试任务数
     *
     * @return 在途重试任务计数
     */
    public int getInFlightRetryCount() {
        return inFlightRetryCount.get();
    }

    /**
     * 获取所有在途总任务计数 (调度 + 重试)
     *
     * @return 总在途计数
     */
    public int getTotalInFlightCount() {
        return inFlightJobCount.get() + inFlightRetryCount.get();
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            draining.set(false);
            log.info("SnailJob 平滑停机排空状态机已启动就绪 (drainTimeout: {})",
                    properties.getDrainTimeout() != null ? properties.getDrainTimeout() : Duration.ofSeconds(30));
        }
    }

    @Override
    public void stop(Runnable callback) {
        if (!running.get()) {
            if (callback != null) {
                callback.run();
            }
            return;
        }

        log.info(">>>>>>>>>>> 触发 SnailJob 三阶平滑停机排空协议 <<<<<<<<<<<");

        // 阶段一：平滑离线标记
        draining.set(true);
        log.info(">>>>> [阶段一/平滑离线] SnailJob 执行器已进入停机排空阶段，拒绝接收新任务与重试。");

        // 阶段二：在途任务与重试排空
        drainInFlightTasks();

        // 阶段三：物理连接与底层资源销毁
        teardownGrpcChannel();

        running.set(false);
        log.info(">>>>>>>>>>> SnailJob 执行器三阶平滑停机排空完成 <<<<<<<<<<<");

        if (callback != null) {
            callback.run();
        }
    }

    @Override
    public void stop() {
        stop(() -> {});
    }

    @Override
    public void destroy() {
        stop(() -> {});
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        // 返回 Integer.MAX_VALUE，确保停机时最优先触发生命周期排空
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /**
     * 等待所有在途调度与重试任务平滑执行完毕
     */
    private void drainInFlightTasks() {
        Duration timeout = (properties != null && properties.getDrainTimeout() != null)
                ? properties.getDrainTimeout()
                : Duration.ofSeconds(30);

        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        log.info(">>>>> [阶段二/排空等待] 正在等待在途任务排空，最长等待超时时限: {} 秒，当前在途: [Job={}, Retry={}]",
                timeout.toSeconds(), inFlightJobCount.get(), inFlightRetryCount.get());

        while (System.nanoTime() < deadlineNanos) {
            boolean jobsDrained = inFlightJobCount.get() <= 0;
            boolean retriesDrained = inFlightRetryCount.get() <= 0;
            boolean poolsIdle = areThreadPoolsIdle();

            if (jobsDrained && retriesDrained && poolsIdle) {
                log.info(">>>>> [阶段二/排空完成] 所有在途调度与重试任务已平滑执行完毕并排空。");
                return;
            }

            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn(">>>>> [阶段二/排空中断] 等待排空期间线程收到中断信号，提前终止等待");
                return;
            }
        }

        log.warn(">>>>> [阶段二/排空超时] 达到最大等待阈值 {} 秒，仍有未完成在途任务: [Job={}, Retry={}]，强制推进后续资源销毁",
                timeout.toSeconds(), inFlightJobCount.get(), inFlightRetryCount.get());
    }

    /**
     * 探测 SnailJob 内部任务线程池是否全部空闲
     */
    private boolean areThreadPoolsIdle() {
        try {
            Field field = ThreadPoolCache.class.getDeclaredField("CACHE_THREAD_POOL");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            ConcurrentHashMap<Long, ThreadPoolExecutor> map = (ConcurrentHashMap<Long, ThreadPoolExecutor>) field.get(null);
            if (map != null && !map.isEmpty()) {
                for (ThreadPoolExecutor executor : map.values()) {
                    if (executor != null && !executor.isShutdown() && (executor.getActiveCount() > 0 || !executor.getQueue().isEmpty())) {
                        return false;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    /**
     * 优雅关闭 SnailJob Netty / gRPC 物理通信通道
     */
    private void teardownGrpcChannel() {
        try {
            Field channelField = GrpcChannel.class.getDeclaredField("channel");
            channelField.setAccessible(true);
            Object chan = channelField.get(null);
            if (chan instanceof ManagedChannel managedChannel) {
                if (!managedChannel.isShutdown()) {
                    log.info(">>>>> [阶段三/连接销毁] 正在优雅关闭 Netty / gRPC ManagedChannel 通道...");
                    managedChannel.shutdown();
                    if (!managedChannel.awaitTermination(3, TimeUnit.SECONDS)) {
                        log.warn(">>>>> [阶段三/连接强制销毁] gRPC 通道未在时限内终止，执行强制 shutdownNow()");
                        managedChannel.shutdownNow();
                    }
                    log.info(">>>>> [阶段三/连接销毁] Netty / gRPC 物理通道关闭完成。");
                }
            }
        } catch (Throwable t) {
            log.debug("Netty/gRPC channel 关闭探测与销毁: {}", t.getMessage());
        }
    }
}
