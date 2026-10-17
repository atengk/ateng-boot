package io.github.atengk.boot.core.context;

/**
 * 用户操作人上下文持有者
 * 基于 ThreadLocal 维护当前请求或线程的用户标识，作为未挂载外部安全框架时的轻量级内存回退机制
 *
 * @author Ateng
 * @since 2026-10-15
 */
public final class UserContextHolder {

    private static final ThreadLocal<String> USER_HOLDER = new ThreadLocal<>();

    private UserContextHolder() {
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
     *
     * @return 操作人唯一标识，若未设置则返回 null
     */
    public static String get() {
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
        String previous = get();
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
}
