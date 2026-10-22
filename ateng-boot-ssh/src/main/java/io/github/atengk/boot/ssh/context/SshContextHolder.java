package io.github.atengk.boot.ssh.context;

import io.github.atengk.boot.ssh.model.SshServerInfo;

import java.util.function.Supplier;

/**
 * SSH 动态路由切源上下文持有者
 * <p>
 * 基于 {@link ThreadLocal} 提供线程级 SSH 服务节点与目标描述符动态切换与路由寻址，
 * 支持函数式闭包与安全嵌套切换，严格遵循确定性现场清理机制防线程脏读与线程池污染。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
public final class SshContextHolder {

    private static final ThreadLocal<String> SERVER_NAME_CONTEXT = new ThreadLocal<>();
    private static final ThreadLocal<SshServerInfo> SERVER_INFO_CONTEXT = new ThreadLocal<>();

    private SshContextHolder() {
    }

    /**
     * 设置当前线程绑定的具名 SSH 服务节点标识
     *
     * @param serverName 节点名称
     */
    public static void setServer(String serverName) {
        if (serverName == null || serverName.isBlank()) {
            SERVER_NAME_CONTEXT.remove();
        } else {
            SERVER_NAME_CONTEXT.set(serverName.trim());
        }
    }

    /**
     * 获取当前线程绑定的具名 SSH 服务节点标识
     *
     * @return 节点名称，若未指定返回 null
     */
    public static String getServer() {
        return SERVER_NAME_CONTEXT.get();
    }

    /**
     * 设置当前线程绑定的动态目标服务器描述符（专职支持 CMDB 动态资产直连）
     *
     * @param serverInfo 目标描述符
     */
    public static void setServerInfo(SshServerInfo serverInfo) {
        if (serverInfo == null) {
            SERVER_INFO_CONTEXT.remove();
        } else {
            SERVER_INFO_CONTEXT.set(serverInfo);
        }
    }

    /**
     * 获取当前线程绑定的动态目标服务器描述符
     *
     * @return 目标描述符，若未指定返回 null
     */
    public static SshServerInfo getServerInfo() {
        return SERVER_INFO_CONTEXT.get();
    }

    /**
     * 清理当前线程的全部路由与连接上下文现场（防线程池污染）
     */
    public static void clear() {
        SERVER_NAME_CONTEXT.remove();
        SERVER_INFO_CONTEXT.remove();
    }

    /**
     * 在指定的具名 SSH 节点上下文中安全执行无返回值任务（支持安全嵌套与确定性复位）
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
                SERVER_NAME_CONTEXT.remove();
            }
        }
    }

    /**
     * 在指定的具名 SSH 节点上下文中安全执行有返回值任务（支持安全嵌套与确定性复位）
     *
     * @param serverName 目标节点名称
     * @param supplier   待执行的计算逻辑
     * @param <T>        返回值类型
     * @return 计算结果
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
                SERVER_NAME_CONTEXT.remove();
            }
        }
    }

    /**
     * 在指定的动态目标描述符上下文中安全执行无返回值任务（支持安全嵌套与确定性复位）
     *
     * @param serverInfo 目标描述符
     * @param action     待执行的逻辑
     */
    public static void runWith(SshServerInfo serverInfo, Runnable action) {
        if (action == null) {
            return;
        }
        SshServerInfo previous = getServerInfo();
        try {
            setServerInfo(serverInfo);
            action.run();
        } finally {
            if (previous != null) {
                setServerInfo(previous);
            } else {
                SERVER_INFO_CONTEXT.remove();
            }
        }
    }

    /**
     * 在指定的动态目标描述符上下文中安全执行有返回值任务（支持安全嵌套与确定性复位）
     *
     * @param serverInfo 目标描述符
     * @param supplier   待执行的计算逻辑
     * @param <T>        返回值类型
     * @return 计算结果
     */
    public static <T> T runWith(SshServerInfo serverInfo, Supplier<T> supplier) {
        if (supplier == null) {
            return null;
        }
        SshServerInfo previous = getServerInfo();
        try {
            setServerInfo(serverInfo);
            return supplier.get();
        } finally {
            if (previous != null) {
                setServerInfo(previous);
            } else {
                SERVER_INFO_CONTEXT.remove();
            }
        }
    }
}
