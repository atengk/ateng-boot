package io.github.atengk.boot.core.context;

import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 异步线程池上下文传播装饰器
 * 抓取当前调用线程的链路追踪标识（MDC）与用户身份标识（UserContextHolder），在异步线程中安全还原并在任务结束后严格清理
 *
 * @author Ateng
 * @since 2026-10-15
 */
public final class AsyncContext {

    private AsyncContext() {
    }

    /**
     * 包装 Runnable 任务以实现异步线程上下文无缝传递与自清理
     *
     * @param task 原始 Runnable 任务
     * @return 增强上下文传播能力的 Runnable 任务，若入参为 null 则返回 null
     */
    public static Runnable wrap(Runnable task) {
        if (task == null) {
            return null;
        }

        // 1. 捕获父线程当前的上下文快照
        Map<String, String> parentMdc = MDC.getCopyOfContextMap();
        String parentUser = UserContextHolder.get();

        return () -> {
            // 2. 暂存异步工作线程原有的上下文现场
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            String previousUser = UserContextHolder.get();

            try {
                // 3. 将父线程上下文注入当前工作线程
                setContext(parentMdc, parentUser);
                task.run();
            } finally {
                // 4. 彻底恢复工作线程执行前的原始状态，严防线程池污染与内存泄露
                setContext(previousMdc, previousUser);
            }
        };
    }

    /**
     * 包装 Callable 任务以实现异步线程上下文无缝传递与自清理
     *
     * @param task 原始 Callable 任务
     * @param <V>  任务返回值泛型
     * @return 增强上下文传播能力的 Callable 任务，若入参为 null 则返回 null
     */
    public static <V> Callable<V> wrap(Callable<V> task) {
        if (task == null) {
            return null;
        }

        // 1. 捕获父线程当前的上下文快照
        Map<String, String> parentMdc = MDC.getCopyOfContextMap();
        String parentUser = UserContextHolder.get();

        return () -> {
            // 2. 暂存异步工作线程原有的上下文现场
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            String previousUser = UserContextHolder.get();

            try {
                // 3. 将父线程上下文注入当前工作线程
                setContext(parentMdc, parentUser);
                return task.call();
            } finally {
                // 4. 彻底恢复工作线程执行前的原始状态，严防线程池污染与内存泄露
                setContext(previousMdc, previousUser);
            }
        };
    }

    private static void setContext(Map<String, String> mdc, String user) {
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
    }
}
