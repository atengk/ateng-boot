package io.github.atengk.boot.mybatisplus.context;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;

/**
 * 多租户上下文持有者
 * 维护当前线程租户上下文与租户忽略标志位的持有者，提供受控编程式闭包与跨线程隔离能力
 *
 * @author Ateng
 * @since 2026-10-16
 */
public final class TenantContextHolder {

    /**
     * 当前线程租户 ID 栈（支持嵌套 runWithTenant 安全回滚还原）
     */
    private static final ThreadLocal<Deque<String>> TENANT_ID_STACK = ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * 当前线程忽略租户深度计数器（> 0 表示处于忽略状态，支持嵌套 runWithoutTenant 安全还原）
     */
    private static final ThreadLocal<Integer> IGNORE_DEPTH = ThreadLocal.withInitial(() -> 0);

    private TenantContextHolder() {
    }

    /**
     * 获取当前线程绑定的租户标识（字符串形式）
     *
     * @return 当前租户标识，若无则返回 null
     */
    public static String getTenantId() {
        Deque<String> stack = TENANT_ID_STACK.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    /**
     * 获取当前线程绑定的租户标识（Long 长整型形式）
     * 若当前租户标识为非纯数字格式或未设置，则返回 null
     *
     * @return 租户标识 Long 值，无法解析时返回 null
     */
    public static Long getTenantIdAsLong() {
        String tenantId = getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(tenantId);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 设置当前线程租户标识（直接覆盖当前层级）
     *
     * @param tenantId 租户标识
     */
    public static void setTenantId(String tenantId) {
        Deque<String> stack = TENANT_ID_STACK.get();
        if (tenantId == null) {
            if (!stack.isEmpty()) {
                stack.pop();
            }
        } else {
            if (!stack.isEmpty()) {
                stack.pop();
            }
            stack.push(tenantId);
        }
    }

    /**
     * 设置当前线程租户标识（Long 重载）
     *
     * @param tenantId 长整型租户标识
     */
    public static void setTenantId(Long tenantId) {
        setTenantId(tenantId != null ? String.valueOf(tenantId) : null);
    }

    /**
     * 判断当前线程是否处于忽略租户过滤状态
     *
     * @return true 表示跳过租户过滤；false 表示执行正常租户拦截
     */
    public static boolean isIgnoreTenant() {
        return IGNORE_DEPTH.get() > 0;
    }

    /**
     * 编程式在指定租户上下文中执行代码块（带返回值）
     * 执行完毕后无论成功与否，均严格恢复原先的租户上下文
     *
     * @param tenantId 临时租户标识
     * @param supplier 业务执行块
     * @param <T>      返回类型
     * @return 执行结果
     */
    public static <T> T runWithTenant(String tenantId, Supplier<T> supplier) {
        Deque<String> stack = TENANT_ID_STACK.get();
        stack.push(tenantId);
        try {
            return supplier.get();
        } finally {
            stack.pop();
            if (stack.isEmpty()) {
                TENANT_ID_STACK.remove();
            }
        }
    }

    /**
     * 编程式在指定租户上下文中执行代码块（无返回值）
     *
     * @param tenantId 临时租户标识
     * @param runnable 业务执行块
     */
    public static void runWithTenant(String tenantId, Runnable runnable) {
        runWithTenant(tenantId, () -> {
            runnable.run();
            return null;
        });
    }

    /**
     * 编程式在指定租户上下文中执行代码块（Long 重载，带返回值）
     *
     * @param tenantId 临时长整型租户标识
     * @param supplier 业务执行块
     * @param <T>      返回类型
     * @return 执行结果
     */
    public static <T> T runWithTenant(Long tenantId, Supplier<T> supplier) {
        return runWithTenant(tenantId != null ? String.valueOf(tenantId) : null, supplier);
    }

    /**
     * 编程式在指定租户上下文中执行代码块（Long 重载，无返回值）
     *
     * @param tenantId 临时长整型租户标识
     * @param runnable 业务执行块
     */
    public static void runWithTenant(Long tenantId, Runnable runnable) {
        runWithTenant(tenantId, () -> {
            runnable.run();
            return null;
        });
    }

    /**
     * 编程式在忽略租户过滤的环境下执行代码块（带返回值）
     * 执行完毕后无论成功与否，均严格恢复进入前的忽略状态
     *
     * @param supplier 业务执行块
     * @param <T>      返回类型
     * @return 执行结果
     */
    public static <T> T runWithoutTenant(Supplier<T> supplier) {
        int depth = IGNORE_DEPTH.get();
        IGNORE_DEPTH.set(depth + 1);
        try {
            return supplier.get();
        } finally {
            if (depth == 0) {
                IGNORE_DEPTH.remove();
            } else {
                IGNORE_DEPTH.set(depth);
            }
        }
    }

    /**
     * 编程式在忽略租户过滤的环境下执行代码块（无返回值）
     *
     * @param runnable 业务执行块
     */
    public static void runWithoutTenant(Runnable runnable) {
        runWithoutTenant(() -> {
            runnable.run();
            return null;
        });
    }

    /**
     * 清理当前线程的所有租户上下文与忽略标记，防止线程池复用污染
     */
    public static void clear() {
        TENANT_ID_STACK.remove();
        IGNORE_DEPTH.remove();
    }
}
