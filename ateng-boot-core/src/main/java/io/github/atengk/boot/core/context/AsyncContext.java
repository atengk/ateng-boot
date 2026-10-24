package io.github.atengk.boot.core.context;

import org.slf4j.MDC;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 异步线程池上下文传播装饰器
 * 抓取当前调用线程的四维/五维核心上下文快照（MDC 链路追踪、User 身份标识、Tenant 租户标识、IgnoreTenant 忽略标志与通过 {@link AsyncContextSnapshotProvider} 扩展的数据源维度等），
 * 在异步工作线程中精准还原并在任务执行结束后严格恢复原始工作线程现场，杜绝线程池污染与跨租户数据越权
 *
 * @author Ateng
 * @since 2026-10-15
 */
public final class AsyncContext {

    private static final List<AsyncContextSnapshotProvider> PROVIDERS = new CopyOnWriteArrayList<>();

    static {
        // 自动发现并加载 SPI 扩展提供者
        try {
            ServiceLoader<AsyncContextSnapshotProvider> loader = ServiceLoader.load(AsyncContextSnapshotProvider.class);
            for (AsyncContextSnapshotProvider provider : loader) {
                PROVIDERS.add(provider);
            }
        } catch (Throwable ignored) {
            // 防御 SPI 加载偶发异常
        }
    }

    private AsyncContext() {
    }

    /**
     * 注册自定义异步上下文快照提供者（支持数据源、鉴权等模块挂载扩展维度）
     *
     * @param provider 上下文快照提供者
     */
    public static void registerSnapshotProvider(AsyncContextSnapshotProvider provider) {
        if (provider != null && !PROVIDERS.contains(provider)) {
            PROVIDERS.add(provider);
        }
    }

    /**
     * 注销已注册的异步上下文快照提供者
     *
     * @param provider 上下文快照提供者
     */
    public static void unregisterSnapshotProvider(AsyncContextSnapshotProvider provider) {
        if (provider != null) {
            PROVIDERS.remove(provider);
        }
    }

    /**
     * 包装 Runnable 任务以实现四维/五维上下文异步传播与自恢复
     *
     * @param task 原始 Runnable 任务
     * @return 增强上下文传播能力的 Runnable 任务，若入参为 null 则返回 null
     */
    public static Runnable wrap(Runnable task) {
        if (task == null) {
            return null;
        }
        ContextSnapshot parent = ContextSnapshot.capture();
        return () -> {
            ContextSnapshot previous = ContextSnapshot.capturePrevious();
            try {
                parent.apply();
                task.run();
            } finally {
                previous.apply();
            }
        };
    }

    /**
     * 包装 Callable 任务以实现四维/五维上下文异步传播与自恢复
     *
     * @param task 原始 Callable 任务
     * @param <V>  任务返回值泛型
     * @return 增强上下文传播能力的 Callable 任务，若入参为 null 则返回 null
     */
    public static <V> Callable<V> wrap(Callable<V> task) {
        if (task == null) {
            return null;
        }
        ContextSnapshot parent = ContextSnapshot.capture();
        return () -> {
            ContextSnapshot previous = ContextSnapshot.capturePrevious();
            try {
                parent.apply();
                return task.call();
            } finally {
                previous.apply();
            }
        };
    }

    /**
     * 包装 Supplier 任务以实现四维/五维上下文异步传播与自恢复
     * 专为 CompletableFuture.supplyAsync 等现代异步场景提供原生支持，避免与 Callable 产生 lambda 重载歧义
     *
     * @param task 原始 Supplier 任务
     * @param <T>  任务返回值泛型
     * @return 增强上下文传播能力的 Supplier 任务，若入参为 null 则返回 null
     */
    public static <T> java.util.function.Supplier<T> wrapSupplier(java.util.function.Supplier<T> task) {
        if (task == null) {
            return null;
        }
        ContextSnapshot parent = ContextSnapshot.capture();
        return () -> {
            ContextSnapshot previous = ContextSnapshot.capturePrevious();
            try {
                parent.apply();
                return task.get();
            } finally {
                previous.apply();
            }
        };
    }

    /**
     * 全链路上下文快照不可变数据载体
     * 统一聚合 MDC 链路、User 身份、Tenant 租户、IgnoreTenant 标志位以及扩展维度（如数据源）
     */
    public static final class ContextSnapshot {
        private final Map<String, String> mdc;
        private final String user;
        private final TenantContextHolder.TenantSnapshot tenant;
        private final Map<AsyncContextSnapshotProvider, Object> extensions;

        private ContextSnapshot(Map<String, String> mdc,
                                String user,
                                TenantContextHolder.TenantSnapshot tenant,
                                Map<AsyncContextSnapshotProvider, Object> extensions) {
            this.mdc = mdc;
            this.user = user;
            this.tenant = tenant;
            this.extensions = extensions != null ? extensions : Collections.emptyMap();
        }

        /**
         * 捕获调用方线程全维度上下文完整快照
         *
         * @return 完整的上下文快照
         */
        public static ContextSnapshot capture() {
            Map<AsyncContextSnapshotProvider, Object> exts = captureExtensions(false);
            return new ContextSnapshot(
                    MDC.getCopyOfContextMap(),
                    UserContextHolder.get(),
                    TenantContextHolder.capture(),
                    exts
            );
        }

        /**
         * 纯净克隆工作线程执行前的上下文现场
         *
         * @return 工作线程执行前的原始现场
         */
        public static ContextSnapshot capturePrevious() {
            Map<AsyncContextSnapshotProvider, Object> exts = captureExtensions(true);
            return new ContextSnapshot(
                    MDC.getCopyOfContextMap(),
                    UserContextHolder.getExplicit(),
                    TenantContextHolder.capturePrevious(),
                    exts
            );
        }

        private static Map<AsyncContextSnapshotProvider, Object> captureExtensions(boolean previous) {
            if (PROVIDERS.isEmpty()) {
                return Collections.emptyMap();
            }
            Map<AsyncContextSnapshotProvider, Object> map = new IdentityHashMap<>(PROVIDERS.size());
            for (AsyncContextSnapshotProvider provider : PROVIDERS) {
                try {
                    Object snap = previous ? provider.capturePrevious() : provider.capture();
                    map.put(provider, snap);
                } catch (Throwable ignored) {
                    // 防御扩展点抛错
                }
            }
            return Collections.unmodifiableMap(map);
        }

        /**
         * 将快照中的上下文状态注入至当前线程（嵌套 try-finally 确保任何单点异常均不阻断后续清理）
         */
        public void apply() {
            try {
                if (this.mdc != null && !this.mdc.isEmpty()) {
                    MDC.setContextMap(this.mdc);
                } else {
                    MDC.clear();
                }
            } finally {
                try {
                    if (this.user != null) {
                        UserContextHolder.set(this.user);
                    } else {
                        UserContextHolder.clear();
                    }
                } finally {
                    try {
                        TenantContextHolder.restore(this.tenant);
                    } finally {
                        applyExtensions();
                    }
                }
            }
        }

        private void applyExtensions() {
            for (Map.Entry<AsyncContextSnapshotProvider, Object> entry : this.extensions.entrySet()) {
                try {
                    entry.getKey().apply(entry.getValue());
                } catch (Throwable ignored) {
                }
            }
        }

        /**
         * 获取指定提供者的快照对象
         *
         * @param provider 提供者对象
         * @return 快照对象
         */
        public Object getExtension(AsyncContextSnapshotProvider provider) {
            return this.extensions.get(provider);
        }
    }
}
