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

        // 1. 捕获父线程当前的四维上下文快照
        Map<String, String> parentMdc = MDC.getCopyOfContextMap();
        String parentUser = UserContextHolder.get();
        TenantContextHolder.TenantSnapshot parentTenant = TenantContextHolder.capture();

        return () -> {
            // 2. 暂存异步工作线程原有的上下文现场
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            String previousUser = UserContextHolder.get();
            TenantContextHolder.TenantSnapshot previousTenant = TenantContextHolder.capturePrevious();

            try {
                // 3. 将父线程上下文注入当前工作线程
                setContext(parentMdc, parentUser, parentTenant);
                task.run();
            } finally {
                // 4. 彻底恢复工作线程执行前的原始状态，严防线程池污染与租户越权
                setContext(previousMdc, previousUser, previousTenant);
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

        // 1. 捕获父线程当前的四维上下文快照
        Map<String, String> parentMdc = MDC.getCopyOfContextMap();
        String parentUser = UserContextHolder.get();
        TenantContextHolder.TenantSnapshot parentTenant = TenantContextHolder.capture();

        return () -> {
            // 2. 暂存异步工作线程原有的上下文现场
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            String previousUser = UserContextHolder.get();
            TenantContextHolder.TenantSnapshot previousTenant = TenantContextHolder.capturePrevious();

            try {
                // 3. 将父线程上下文注入当前工作线程
                setContext(parentMdc, parentUser, parentTenant);
                return task.call();
            } finally {
                // 4. 彻底恢复工作线程执行前的原始状态，严防线程池污染与租户越权
                setContext(previousMdc, previousUser, previousTenant);
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

        // 1. 捕获父线程当前的四维上下文快照
        Map<String, String> parentMdc = MDC.getCopyOfContextMap();
        String parentUser = UserContextHolder.get();
        TenantContextHolder.TenantSnapshot parentTenant = TenantContextHolder.capture();

        return () -> {
            // 2. 暂存异步工作线程原有的上下文现场
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            String previousUser = UserContextHolder.get();
            TenantContextHolder.TenantSnapshot previousTenant = TenantContextHolder.capturePrevious();

            try {
                // 3. 将父线程上下文注入当前工作线程
                setContext(parentMdc, parentUser, parentTenant);
                return task.get();
            } finally {
                // 4. 彻底恢复工作线程执行前的原始状态，严防线程池污染与租户越权
                setContext(previousMdc, previousUser, previousTenant);
            }
        };
    }

    private static void setContext(Map<String, String> mdc, String user, TenantContextHolder.TenantSnapshot tenant) {
        if (mdc != null && !mdc.isEmpty()) {
            MDC.setContextMap(mdc);
        } else {
            MDC.clear();
        }

        if (user != null) {
            UserContextHolder.set(user);
        } else {
            UserContextHolder.clear();
        }

        TenantContextHolder.restore(tenant);
    }
}
