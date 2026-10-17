package io.github.atengk.boot.core.context;

import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 异步线程池上下文传播装饰器
 * 抓取当前调用线程的四维上下文快照（MDC 链路追踪、User 身份标识、Tenant 租户标识与 IgnoreTenant 忽略标志），
 * 在异步工作线程中精准还原并在任务执行结束后严格恢复原始工作线程现场，杜绝线程池污染与跨租户数据越权
 *
 * @author Ateng
 * @since 2026-10-15
 */
public final class AsyncContext {

    private AsyncContext() {
    }

    /**
     * 包装 Runnable 任务以实现四维上下文异步传播与自恢复
     *
     * @param task 原始 Runnable 任务
     * @return 增强四维上下文传播能力的 Runnable 任务，若入参为 null 则返回 null
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
     * 包装 Callable 任务以实现四维上下文异步传播与自恢复
     *
     * @param task 原始 Callable 任务
     * @param <V>  任务返回值泛型
     * @return 增强四维上下文传播能力的 Callable 任务，若入参为 null 则返回 null
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
     * 包装 Supplier 任务以实现四维上下文异步传播与自恢复
     * 专为 CompletableFuture.supplyAsync 等现代异步场景提供原生支持，避免与 Callable 产生 lambda 重载歧义
     *
     * @param task 原始 Supplier 任务
     * @param <T>  任务返回值泛型
     * @return 增强四维上下文传播能力的 Supplier 任务，若入参为 null 则返回 null
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
     * 四维上下文快照不可变数据载体
     * 统一聚合 MDC 链路、User 身份、Tenant 租户与 IgnoreTenant 标志位
     */
    public static final class ContextSnapshot {
        private final Map<String, String> mdc;
        private final String user;
        private final TenantContextHolder.TenantSnapshot tenant;

        private ContextSnapshot(Map<String, String> mdc, String user, TenantContextHolder.TenantSnapshot tenant) {
            this.mdc = mdc;
            this.user = user;
            this.tenant = tenant;
        }

        /**
         * 捕获调用方线程四维上下文完整快照
         *
         * @return 完整的上下文快照
         */
        public static ContextSnapshot capture() {
            return new ContextSnapshot(
                    MDC.getCopyOfContextMap(),
                    UserContextHolder.get(),
                    TenantContextHolder.capture()
            );
        }

        /**
         * 纯净克隆工作线程执行前的上下文现场
         *
         * @return 工作线程执行前的原始现场
         */
        public static ContextSnapshot capturePrevious() {
            return new ContextSnapshot(
                    MDC.getCopyOfContextMap(),
                    UserContextHolder.getExplicit(),
                    TenantContextHolder.capturePrevious()
            );
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
                    TenantContextHolder.restore(this.tenant);
                }
            }
        }
    }
}
