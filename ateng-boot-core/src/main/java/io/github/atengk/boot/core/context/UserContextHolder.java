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
     * 获取当前线程绑定的操作人唯一标识
     *
     * @return 操作人唯一标识，若未设置则返回 null
     */
    public static String get() {
        return USER_HOLDER.get();
    }

    /**
     * 清理当前线程绑定的操作人标识，严防线程池复用导致的上下文泄漏
     */
    public static void clear() {
        USER_HOLDER.remove();
    }
}
