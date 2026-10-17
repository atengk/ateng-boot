package io.github.atengk.boot.storage.context;

import io.github.atengk.boot.core.context.AsyncContext;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * 线程级活动存储源上下文持有者
 * 维护当前线程绑定的动态存储源名称（如 "local"、"oss-public"、"minio-secure"）；
 * 并无缝接入 Core 模块的 AsyncContext 四维快照机制，在跨线程池、@Async 或虚拟线程调度时实现透传与严格自恢复
 *
 * @author Ateng
 * @since 2026-10-17
 */
public final class StorageContextHolder {

    private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

    private StorageContextHolder() {
    }

    /**
     * 设置当前线程绑定的存储源名称
     *
     * @param sourceKey 目标存储源名称，若为空白则等同于清除
     */
    public static void set(String sourceKey) {
        if (sourceKey == null || sourceKey.isBlank()) {
            clear();
        } else {
            CONTEXT.set(sourceKey.trim());
        }
    }

    /**
     * 获取当前线程绑定的存储源名称
     *
     * @return 存储源名称，若未设置返回 null
     */
    public static String get() {
        return CONTEXT.get();
    }

    /**
     * 清理当前线程绑定的存储源上下文
     */
    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * 捕获当前线程的存储源快照
     *
     * @return 当前存储源快照字符串
     */
    public static String capture() {
        return CONTEXT.get();
    }

    /**
     * 恢复工作线程执行前的存储源上下文快照
     *
     * @param previous 恢复目标快照
     */
    public static void restore(String previous) {
        if (previous != null && !previous.isBlank()) {
            CONTEXT.set(previous.trim());
        } else {
            CONTEXT.remove();
        }
    }

    /**
     * 包装 Runnable 任务以实现存储上下文与 Core 四维上下文的跨线程异步透传与自恢复
     *
     * @param task 原始 Runnable 任务
     * @return 增强上下文透传能力的 Runnable 任务
     */
    public static Runnable wrap(Runnable task) {
        if (task == null) {
            return null;
        }
        String parentStorage = capture();
        Runnable wrappedByAsync = AsyncContext.wrap(task);
        return () -> {
            String previousStorage = capture();
            try {
                restore(parentStorage);
                wrappedByAsync.run();
            } finally {
                restore(previousStorage);
            }
        };
    }

    /**
     * 包装 Callable 任务以实现存储上下文与 Core 四维上下文的跨线程异步透传与自恢复
     *
     * @param task 原始 Callable 任务
     * @param <V>  返回值类型泛型
     * @return 增强上下文透传能力的 Callable 任务
     */
    public static <V> Callable<V> wrap(Callable<V> task) {
        if (task == null) {
            return null;
        }
        String parentStorage = capture();
        Callable<V> wrappedByAsync = AsyncContext.wrap(task);
        return () -> {
            String previousStorage = capture();
            try {
                restore(parentStorage);
                return wrappedByAsync.call();
            } finally {
                restore(previousStorage);
            }
        };
    }

    /**
     * 包装 Supplier 任务以实现存储上下文与 Core 四维上下文的跨线程异步透传与自恢复
     * 专为 CompletableFuture.supplyAsync 等场景设计
     *
     * @param task 原始 Supplier 任务
     * @param <T>  返回值类型泛型
     * @return 增强上下文透传能力的 Supplier 任务
     */
    public static <T> Supplier<T> wrapSupplier(Supplier<T> task) {
        if (task == null) {
            return null;
        }
        String parentStorage = capture();
        Supplier<T> wrappedByAsync = AsyncContext.wrapSupplier(task);
        return () -> {
            String previousStorage = capture();
            try {
                restore(parentStorage);
                return wrappedByAsync.get();
            } finally {
                restore(previousStorage);
            }
        };
    }
}
