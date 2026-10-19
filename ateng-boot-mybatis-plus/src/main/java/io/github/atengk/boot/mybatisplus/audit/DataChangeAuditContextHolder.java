package io.github.atengk.boot.mybatisplus.audit;

import java.util.concurrent.Callable;

/**
 * 数据变更审计上下文持有者
 * 提供线程安全的编程式闭包（runWithoutAudit / callWithoutAudit）用于跳过变更审计快照抓取与 Diff 计算
 *
 * @author Ateng
 * @since 2026-10-19
 */
public final class DataChangeAuditContextHolder {

    private static final ThreadLocal<Boolean> AUDIT_DISABLED = new ThreadLocal<>();

    private DataChangeAuditContextHolder() {
    }

    /**
     * 判断当前线程是否已禁用数据变更审计
     *
     * @return true 表示已禁用，false 表示未禁用
     */
    public static boolean isAuditDisabled() {
        return Boolean.TRUE.equals(AUDIT_DISABLED.get());
    }

    /**
     * 临时禁用当前线程的数据变更审计
     */
    public static void disable() {
        AUDIT_DISABLED.set(Boolean.TRUE);
    }

    /**
     * 恢复/清理当前线程的数据变更审计状态
     */
    public static void clear() {
        AUDIT_DISABLED.remove();
    }

    /**
     * 在禁用数据变更审计的环境下执行无返回值的任务
     *
     * @param runnable 待执行任务
     */
    public static void runWithoutAudit(Runnable runnable) {
        if (runnable == null) {
            return;
        }
        boolean previous = isAuditDisabled();
        try {
            disable();
            runnable.run();
        } finally {
            if (previous) {
                disable();
            } else {
                clear();
            }
        }
    }

    /**
     * 在禁用数据变更审计的环境下执行有返回值的任务
     *
     * @param callable 待执行任务
     * @param <T>      返回值类型
     * @return 任务返回值
     * @throws Exception 执行异常
     */
    public static <T> T callWithoutAudit(Callable<T> callable) throws Exception {
        if (callable == null) {
            return null;
        }
        boolean previous = isAuditDisabled();
        try {
            disable();
            return callable.call();
        } finally {
            if (previous) {
                disable();
            } else {
                clear();
            }
        }
    }
}
