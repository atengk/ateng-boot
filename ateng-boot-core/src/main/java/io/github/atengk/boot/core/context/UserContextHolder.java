package io.github.atengk.boot.core.context;

import io.github.atengk.boot.core.spi.CurrentUserProvider;

import java.util.Deque;
import java.util.LinkedList;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 用户操作人上下文持有者
 * 基于惰性初始化 ThreadLocal 栈维护当前请求或线程的用户标识，支持多层嵌套闭包还原、SPI 自动回退与跨线程快照隔离
 *
 * @author Ateng
 * @since 2026-10-15
 */
public final class UserContextHolder {

    private static final ThreadLocal<Deque<String>> USER_STACK = new ThreadLocal<>();

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
     * 设置当前线程绑定的操作人唯一标识（覆盖当前层级）
     *
     * @param userId 操作人唯一标识
     */
    public static void set(String userId) {
        ContextStackSupport.setTop(USER_STACK, userId);
    }

    /**
     * 设置当前线程绑定的操作人唯一标识（Long 重载）
     *
     * @param userId 长整型操作人唯一标识
     */
    public static void set(Long userId) {
        set(userId != null ? String.valueOf(userId) : null);
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
     * 设置当前线程绑定的操作人唯一标识（set(Long) 别名）
     *
     * @param userId 长整型操作人唯一标识
     */
    public static void setUserId(Long userId) {
        set(userId);
    }

    /**
     * 获取当前线程绑定的操作人唯一标识
     * 优先从当前线程显式上下文栈中提取，若未设置则安全回退至 {@link CurrentUserProvider} SPI
     *
     * @return 操作人唯一标识，若未设置且无 SPI 提供者则返回 null
     */
    public static String get() {
        String user = ContextStackSupport.peekExplicit(USER_STACK);
        if (user != null && !user.isBlank()) {
            return user;
        }
        CurrentUserProvider provider = userProvider;
        if (provider != null) {
            try {
                return provider.getCurrentUser();
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
        return ContextStackSupport.peekExplicit(USER_STACK);
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
     * 获取当前线程绑定的操作人唯一标识（Long 长整型形式）
     * 若未设置或为空白字符串返回 null；若当前用户标识非合法数字格式则快速失败抛出 {@link IllegalStateException}
     *
     * @return 操作人唯一标识 Long 值，未设置时返回 null
     * @throws IllegalStateException 当用户标识非合法数字格式时抛出
     */
    public static Long getUserIdAsLong() {
        String userId = get();
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(userId.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("当前用户标识 [" + userId + "] 无法解析为有效的 Long 长整型", ex);
        }
    }

    /**
     * 判断当前是否处于身份模拟态 (RunAs)
     *
     * @return true 表示处于身份模拟态
     */
    public static boolean isRunAs() {
        CurrentUserProvider provider = userProvider;
        if (provider != null) {
            try {
                return provider.isRunAs();
            } catch (Throwable ignored) {
                // 防御性隔离外部 SPI 抛出的未知异常，保底返回 false
            }
        }
        return false;
    }

    /**
     * 获取真实原始操作人唯一标识（仅在处于身份模拟 RunAs 时非空）
     *
     * @return 原始操作人标识，非模拟态返回 null
     */
    public static String getOriginalUserId() {
        CurrentUserProvider provider = userProvider;
        if (provider != null) {
            try {
                return provider.getOriginalUser();
            } catch (Throwable ignored) {
                // 防御性隔离外部 SPI 抛出的未知异常，保底返回 null
            }
        }
        return null;
    }

    /**
     * 编程式在指定用户上下文中执行代码块（带返回值）
     * 执行完毕后无论成功与否，均按栈式层级严格恢复原先的上下文现场
     *
     * @param userId   临时操作人唯一标识
     * @param supplier 业务执行体
     * @param <T>      返回值泛型
     * @return 执行结果
     */
    public static <T> T runWithUser(String userId, Supplier<T> supplier) {
        return ContextStackSupport.pushAndRun(USER_STACK, userId, supplier);
    }

    /**
     * 编程式在指定用户上下文中执行代码块（无返回值）
     *
     * @param userId   临时操作人唯一标识
     * @param runnable 业务执行体
     */
    public static void runWithUser(String userId, Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable 不能为空");
        runWithUser(userId, () -> {
            runnable.run();
            return null;
        });
    }

    /**
     * 编程式在指定用户上下文中执行代码块（Long 重载，带返回值）
     *
     * @param userId   临时长整型操作人唯一标识
     * @param supplier 业务执行体
     * @param <T>      返回值泛型
     * @return 执行结果
     */
    public static <T> T runWithUser(Long userId, Supplier<T> supplier) {
        return runWithUser(userId != null ? String.valueOf(userId) : null, supplier);
    }

    /**
     * 编程式在指定用户上下文中执行代码块（Long 重载，无返回值）
     *
     * @param userId   临时长整型操作人唯一标识
     * @param runnable 业务执行体
     */
    public static void runWithUser(Long userId, Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable 不能为空");
        runWithUser(userId, () -> {
            runnable.run();
            return null;
        });
    }

    /**
     * 清理当前线程绑定的操作人标识栈，严防线程池复用导致的上下文泄漏
     */
    public static void clear() {
        USER_STACK.remove();
    }

    /**
     * 清理当前线程绑定的操作人标识栈（clear 别名）
     */
    public static void remove() {
        clear();
    }

    /**
     * 清理当前线程绑定的操作人标识栈（clear 别名）
     */
    public static void clearUser() {
        clear();
    }

    /**
     * 抓取当前线程的用户状态快照
     * 若当前显式栈为空但通过 SPI 能获取到操作人标识，则固化在快照中以确保跨线程不丢失
     *
     * @return 用户状态快照
     */
    public static UserSnapshot capture() {
        Deque<String> stackCopy = ContextStackSupport.copyStack(USER_STACK);
        if (stackCopy.isEmpty()) {
            String effectiveUser = get();
            if (effectiveUser != null && !effectiveUser.isBlank()) {
                stackCopy.push(effectiveUser);
            }
        }
        return new UserSnapshot(stackCopy);
    }

    /**
     * 纯净克隆当前线程实际显式持有的用户上下文现场（不触发 SPI 回退解析）
     * 专供异步工作线程在执行任务前暂存现场，并在任务完成后精准恢复
     *
     * @return 当前线程的原始用户现场快照
     */
    public static UserSnapshot capturePrevious() {
        return new UserSnapshot(ContextStackSupport.copyStack(USER_STACK));
    }

    /**
     * 将快照中的用户上下文状态恢复至当前工作线程
     *
     * @param snapshot 用户状态快照
     */
    public static void restore(UserSnapshot snapshot) {
        if (snapshot == null || snapshot.userStack.isEmpty()) {
            clear();
        } else {
            ContextStackSupport.restoreStack(USER_STACK, snapshot.userStack);
        }
    }

    /**
     * 检测当前线程是否仍绑定了非空的 ThreadLocal 用户栈（仅供同包单测核验惰性清理使用）
     *
     * @return true 表示存在已绑定的 ThreadLocal 栈对象
     */
    static boolean hasActiveThreadLocal() {
        return ContextStackSupport.hasNonNullValue(USER_STACK);
    }

    /**
     * 用户跨线程状态快照不可变数据结构
     */
    public static final class UserSnapshot {
        private final Deque<String> userStack;

        private UserSnapshot(Deque<String> userStack) {
            this.userStack = userStack != null ? userStack : new LinkedList<>();
        }

        /**
         * 获取快照栈顶的操作人唯一标识
         *
         * @return 操作人标识，无则返回 null
         */
        public String getUserId() {
            return userStack.isEmpty() ? null : userStack.peek();
        }
    }
}
