package io.github.atengk.boot.ftp.context;

import java.util.function.Supplier;

/**
 * FTP 动态路由切源上下文持有者
 * <p>
 * 基于 {@link ThreadLocal} 提供线程级 FTP 服务节点动态切换与路由寻址，
 * 支持函数式闭包与安全嵌套切换，严格遵循确定性现场清理机制防线程脏读。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
public final class FtpContextHolder {

    private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

    private FtpContextHolder() {
    }

    /**
     * 设置当前线程绑定的 FTP 服务节点名称
     *
     * @param serverName 节点名称
     */
    public static void setServer(String serverName) {
        if (serverName == null || serverName.isBlank()) {
            clear();
        } else {
            CONTEXT.set(serverName);
        }
    }

    /**
     * 获取当前线程绑定的 FTP 服务节点名称
     *
     * @return 节点名称，若未指定返回 null
     */
    public static String getServer() {
        return CONTEXT.get();
    }

    /**
     * 清理当前线程的路由上下文现场（防线程池污染）
     */
    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * 在指定的 FTP 节点上下文中安全执行无返回值任务（支持安全嵌套与确定性复位）
     *
     * @param serverName 目标节点名称
     * @param action     待执行的逻辑
     */
    public static void runWith(String serverName, Runnable action) {
        if (action == null) {
            return;
        }
        String previous = getServer();
        try {
            setServer(serverName);
            action.run();
        } finally {
            if (previous != null) {
                setServer(previous);
            } else {
                clear();
            }
        }
    }

    /**
     * 在指定的 FTP 节点上下文中安全执行有返回值任务（支持安全嵌套与确定性复位）
     *
     * @param serverName 目标节点名称
     * @param supplier   待执行的计算逻辑
     * @param <T>        返回值类型
     * @return 执行结果
     */
    public static <T> T runWith(String serverName, Supplier<T> supplier) {
        if (supplier == null) {
            return null;
        }
        String previous = getServer();
        try {
            setServer(serverName);
            return supplier.get();
        } finally {
            if (previous != null) {
                setServer(previous);
            } else {
                clear();
            }
        }
    }
}
