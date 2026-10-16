package io.github.atengk.boot.mybatisplus.context;

import java.util.function.Supplier;

/**
 * 细粒度数据权限上下文持有者
 * 维护当前线程数据权限忽略标志与超级管理员身份状态，提供线程隔离与受控编程式闭包能力
 *
 * @author Ateng
 * @since 2026-10-16
 */
public final class DataPermissionContextHolder {

    /**
     * 当前线程忽略数据权限深度计数器（> 0 表示处于忽略状态，支持嵌套 runWithoutPermission 安全还原）
     */
    private static final ThreadLocal<Integer> IGNORE_DEPTH = ThreadLocal.withInitial(() -> 0);

    /**
     * 当前线程超级管理员身份深度计数器（> 0 表示拥有超管身份自动短路放行，支持嵌套 runAsSuperAdmin 安全还原）
     */
    private static final ThreadLocal<Integer> SUPER_ADMIN_DEPTH = ThreadLocal.withInitial(() -> 0);

    private DataPermissionContextHolder() {
    }

    /**
     * 判断当前线程是否处于忽略数据权限过滤状态
     *
     * @return true 表示跳过数据权限过滤；false 表示执行正常数据权限过滤
     */
    public static boolean isIgnore() {
        return IGNORE_DEPTH.get() > 0;
    }

    /**
     * 设置当前线程是否忽略数据权限过滤
     *
     * @param ignore true 表示忽略；false 表示不忽略
     */
    public static void setIgnore(boolean ignore) {
        if (ignore) {
            IGNORE_DEPTH.set(Math.max(1, IGNORE_DEPTH.get()));
        } else {
            IGNORE_DEPTH.set(0);
            IGNORE_DEPTH.remove();
        }
    }

    /**
     * 判断当前线程是否具备超级管理员身份（自动短路放行）
     *
     * @return true 表示具备超管身份；false 表示不具备
     */
    public static boolean isSuperAdmin() {
        return SUPER_ADMIN_DEPTH.get() > 0;
    }

    /**
     * 设置当前线程超级管理员身份标识
     *
     * @param superAdmin true 表示具备超管身份；false 表示不具备
     */
    public static void setSuperAdmin(boolean superAdmin) {
        if (superAdmin) {
            SUPER_ADMIN_DEPTH.set(Math.max(1, SUPER_ADMIN_DEPTH.get()));
        } else {
            SUPER_ADMIN_DEPTH.set(0);
            SUPER_ADMIN_DEPTH.remove();
        }
    }

    /**
     * 编程式在忽略数据权限的环境下执行代码块（带返回值）
     * 执行完毕后无论成功与否，均严格恢复原先的忽略状态深度
     *
     * @param supplier 业务执行块
     * @param <T>      返回类型
     * @return 执行结果
     */
    public static <T> T runWithoutPermission(Supplier<T> supplier) {
        int currentDepth = IGNORE_DEPTH.get();
        IGNORE_DEPTH.set(currentDepth + 1);
        try {
            return supplier.get();
        } finally {
            if (currentDepth <= 0) {
                IGNORE_DEPTH.remove();
            } else {
                IGNORE_DEPTH.set(currentDepth);
            }
        }
    }

    /**
     * 编程式在忽略数据权限的环境下执行代码块（无返回值）
     *
     * @param runnable 业务执行块
     */
    public static void runWithoutPermission(Runnable runnable) {
        runWithoutPermission(() -> {
            runnable.run();
            return null;
        });
    }

    /**
     * 编程式在超级管理员身份下执行代码块（带返回值，自动短路放行）
     * 执行完毕后无论成功与否，均严格恢复原先的超管身份状态深度
     *
     * @param supplier 业务执行块
     * @param <T>      返回类型
     * @return 执行结果
     */
    public static <T> T runAsSuperAdmin(Supplier<T> supplier) {
        int currentDepth = SUPER_ADMIN_DEPTH.get();
        SUPER_ADMIN_DEPTH.set(currentDepth + 1);
        try {
            return supplier.get();
        } finally {
            if (currentDepth <= 0) {
                SUPER_ADMIN_DEPTH.remove();
            } else {
                SUPER_ADMIN_DEPTH.set(currentDepth);
            }
        }
    }

    /**
     * 编程式在超级管理员身份下执行代码块（无返回值，自动短路放行）
     *
     * @param runnable 业务执行块
     */
    public static void runAsSuperAdmin(Runnable runnable) {
        runAsSuperAdmin(() -> {
            runnable.run();
            return null;
        });
    }

    /**
     * 清理当前线程所有数据权限上下文状态（常用于异步线程池复用或请求生命周期完结后）
     */
    public static void clear() {
        IGNORE_DEPTH.remove();
        SUPER_ADMIN_DEPTH.remove();
    }
}
