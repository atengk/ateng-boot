package io.github.atengk.boot.core.context;

import io.github.atengk.boot.core.spi.CurrentUserProvider;

/**
 * 用户操作人上下文持有者
 * 基于 ThreadLocal 维护当前请求或线程的用户标识，支持编程式闭包、SPI 自动回退与跨线程隔离能力
 *
 * @author Ateng
 * @since 2026-10-15
 */
public final class UserContextHolder {

    private static final ThreadLocal<String> USER_HOLDER = new ThreadLocal<>();

    /**
     * 外部用户操作人提取 SPI
     */
    private static volatile CurrentUserProvider userProvider;

    private UserContextHolder() {
    }

    /**
     * 注册或更新全局用户操作人提取 SPI
     *
     * @param provider 用户提取提供者
     */
    public static void setUserProvider(CurrentUserProvider provider) {
        userProvider = provider;
    }

    /**
     * 获取当前全局配置的用户操作人提取 SPI
     *
     * @return 当前用户 SPI，未配置返回 null
     */
    public static CurrentUserProvider getUserProvider() {
        return userProvider;
    }

    /**
     * 设置当前线程绑定的操作人唯一标识
     *
     * @param userId 操作人唯一标识
     */
    public static void set(String userId) {
        USER_HOLDER.set(userId);
    }

    /**
     * 设置当前线程绑定的操作人唯一标识（set 别名）
     *
     * @param userId 操作人唯一标识
     */
    public static void setUserId(String userId) {
        set(userId);
    }

    /**
     * 获取当前线程绑定的操作人唯一标识
     * 优先从当前线程显式上下文中提取，若未设置则安全回退至 {@link CurrentUserProvider} SPI
     *
     * @return 操作人唯一标识，若未设置且无 SPI 提供者则返回 null
     */
    public static String get() {
        String user = USER_HOLDER.get();
        if (user != null && !user.isBlank()) {
            return user;
        }
        if (userProvider != null) {
            try {
                return userProvider.getCurrentUser();
            } catch (Throwable ignored) {
                // 防御外部 SPI 实现异常中断主业务逻辑
            }
        }
        return null;
    }

    /**
     * 获取当前线程显式绑定的操作人唯一标识（纯净提取，不触发外部 SPI 回退）
     * 专供上下文现场暂存、恢复与防污染快照，确保无状态工作线程不被全局 SPI 用户固化写入
     *
     * @return 当前线程显式绑定的操作人，未显式绑定则返回 null
     */
    public static String getExplicit() {
        return USER_HOLDER.get();
    }

    /**
     * 获取当前线程绑定的操作人唯一标识（get 别名）
     *
     * @return 操作人唯一标识，若未设置则返回 null
     */
    public static String getUserId() {
        return get();
    }

    /**
     * 编程式在指定用户上下文中执行代码块（带返回值）
     * 执行完毕后无论成功与否，均严格恢复原先的上下文现场
     *
     * @param userId   临时操作人唯一标识
     * @param supplier 业务执行体
     * @param <T>      返回值泛型
     * @return 执行结果
     */
    public static <T> T runWithUser(String userId, java.util.function.Supplier<T> supplier) {
        java.util.Objects.requireNonNull(supplier, "supplier 不能为空");
        String previous = USER_HOLDER.get();
        set(userId);
        try {
            return supplier.get();
        } finally {
            if (previous != null) {
                set(previous);
            } else {
                clear();
            }
        }
    }

    /**
     * 编程式在指定用户上下文中执行代码块（无返回值）
     *
     * @param userId   临时操作人唯一标识
     * @param runnable 业务执行体
     */
    public static void runWithUser(String userId, Runnable runnable) {
        java.util.Objects.requireNonNull(runnable, "runnable 不能为空");
        runWithUser(userId, () -> {
            runnable.run();
            return null;
        });
    }

    /**
     * 清理当前线程绑定的操作人标识，严防线程池复用导致的上下文泄漏
     */
    public static void clear() {
        USER_HOLDER.remove();
    }

    /**
     * 清理当前线程绑定的操作人标识（clear 别名）
     */
    public static void remove() {
        clear();
    }

    /**
     * 清理当前线程绑定的操作人标识（clear 别名）
     */
    public static void clearUser() {
        clear();
    }
}
