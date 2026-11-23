package io.github.atengk.boot.job.snail.context;

/**
 * SnailJob 调度上下文线程绑定持有者
 *
 * <p>维护当前执行线程的 {@link SnailJobContext} 实例，并在切面退出时无条件执行重置与清理，
 * 杜绝在多任务调度与受管线程池复用场景下发生上下文泄漏或脏读。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public final class SnailJobContextHolder {

    private static final ThreadLocal<SnailJobContext> CONTEXT_HOLDER = new ThreadLocal<>();

    private SnailJobContextHolder() {
    }

    /**
     * 获取当前线程绑定的 SnailJob 上下文
     *
     * @return SnailJobContext 实例，未绑定时返回 null
     */
    public static SnailJobContext get() {
        return CONTEXT_HOLDER.get();
    }

    /**
     * 获取当前线程绑定的 SnailJob 上下文（get() 语义别名）
     *
     * @return SnailJobContext 实例，未绑定时返回 null
     */
    public static SnailJobContext getContext() {
        return get();
    }

    /**
     * 获取当前上下文中绑定的 traceId（若未设置则保底从 MDC 提取）
     *
     * @return traceId
     */
    public static String getTraceId() {
        return org.slf4j.MDC.get("traceId");
    }

    /**
     * 获取当前上下文中绑定的租户标识（若未设置则保底从 TenantContextHolder 提取）
     *
     * @return 租户标识
     */
    public static String getTenantId() {
        return io.github.atengk.boot.core.context.TenantContextHolder.getTenantId();
    }

    /**
     * 绑定指定 SnailJob 上下文到当前线程
     *
     * @param context SnailJobContext 实例
     */
    public static void set(SnailJobContext context) {
        if (context != null) {
            CONTEXT_HOLDER.set(context);
        } else {
            CONTEXT_HOLDER.remove();
        }
    }

    /**
     * 清理当前线程绑定的上下文现场
     */
    public static void remove() {
        CONTEXT_HOLDER.remove();
    }

    /**
     * 清理当前线程绑定的上下文现场（remove() 语义别名）
     */
    public static void clear() {
        remove();
    }
}
