package io.github.atengk.boot.core.enums;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 平台线程池任务饱和拒绝策略枚举
 * 映射 JDK 标准 ThreadPoolExecutor 饱和拒绝处理器，提供强类型配置与安全实例转换
 *
 * @author Ateng
 * @since 2026-10-22
 */
public enum AsyncRejectedPolicy {

    /**
     * 调用者运行策略（默认）：由提交任务的调用线程直接执行，形成天然背压（Backpressure），保障高并发下任务不丢失
     */
    CALLER_RUNS(new ThreadPoolExecutor.CallerRunsPolicy()),

    /**
     * 中止策略：抛出 RejectedExecutionException 异常快速失败
     */
    ABORT(new ThreadPoolExecutor.AbortPolicy()),

    /**
     * 静默丢弃策略：直接丢弃无法执行的任务，不抛出异常
     */
    DISCARD(new ThreadPoolExecutor.DiscardPolicy()),

    /**
     * 丢弃最老任务策略：丢弃阻塞队列头部的最早任务，随后重新尝试执行当前任务
     */
    DISCARD_OLDEST(new ThreadPoolExecutor.DiscardOldestPolicy());

    private final RejectedExecutionHandler handler;

    AsyncRejectedPolicy(RejectedExecutionHandler handler) {
        this.handler = handler;
    }

    /**
     * 获取对应的 JDK 原生拒绝策略处理器实例
     *
     * @return JDK RejectedExecutionHandler 实例
     */
    public RejectedExecutionHandler getHandler() {
        return handler;
    }
}
