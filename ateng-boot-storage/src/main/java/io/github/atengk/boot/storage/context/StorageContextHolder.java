package io.github.atengk.boot.storage.context;

import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.AsyncContextSnapshotProvider;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * 线程级活动存储源上下文持有者
 * 维护当前线程绑定的动态存储源名称（如 "local"、"oss-public"、"minio-secure"）；
 * 并无缝接入 Core 模块的 AsyncContext 快照机制，在跨线程池、@Async 或虚拟线程调度时实现五维透传与严格自恢复
 *
 * @author Ateng
 * @since 2026-10-17
 */
public final class StorageContextHolder {

    /**
     * 默认存储源标识（空白输入平滑回退目标）
     */
    public static final String DEFAULT_SOURCE = "default";

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

    /**
     * 获取当前线程绑定的存储数据源标识（{@link #get()} 的语义化别名）
     *
     * @return 存储数据源标识，若未绑定则返回 null
     */
    public static String getDataSource() {
        return get();
    }

    /**
     * 在指定存储源作用域内执行带返回值的代码块（异常安全且支持多层嵌套）
     * 进入时保存外层上下文并切换至目标存储源（传入 null 或空白字符串自动回退至 {@link #DEFAULT_SOURCE}），
     * 执行结束或发生异常时均在 finally 中严格恢复进入前的存储源上下文
     *
     * @param sourceKey 目标存储源标识（null 或空白时回退为 "default"）
     * @param action    待执行业务逻辑块，不能为 null
     * @param <T>       返回值类型泛型
     * @return 业务逻辑执行结果
     */
    public static <T> T executeWith(String sourceKey, Supplier<T> action) {
        Objects.requireNonNull(action, "action must not be null");
        String previous = capture();
        String target = (sourceKey == null || sourceKey.isBlank()) ? DEFAULT_SOURCE : sourceKey.trim();
        set(target);
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    /**
     * 在指定存储源作用域内执行无返回值的代码块（异常安全且支持多层嵌套）
     * 进入时保存外层上下文并切换至目标存储源（传入 null 或空白字符串自动回退至 {@link #DEFAULT_SOURCE}），
     * 执行结束或发生异常时均在 finally 中严格恢复进入前的存储源上下文
     *
     * @param sourceKey 目标存储源标识（null 或空白时回退为 "default"）
     * @param action    待执行业务逻辑块，不能为 null
     */
    public static void runWith(String sourceKey, Runnable action) {
        Objects.requireNonNull(action, "action must not be null");
        executeWith(sourceKey, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 存储源维度的异步全链路上下文快照扩展提供者实现
     * 协同 Core 模块 {@link AsyncContext} 形成五维上下文自动透传与工作线程现场安全恢复
     */
    public static final class StorageAsyncContextSnapshotProvider implements AsyncContextSnapshotProvider {

        @Override
        public Object capture() {
            return StorageContextHolder.capture();
        }

        @Override
        public void apply(Object snapshot) {
            StorageContextHolder.restore(snapshot instanceof String source ? source : null);
        }

        @Override
        public void restore(Object previous) {
            StorageContextHolder.restore(previous instanceof String source ? source : null);
        }
    }
}
