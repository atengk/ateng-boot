package io.github.atengk.boot.core.context;

import io.github.atengk.boot.core.spi.CurrentTenantProvider;

import java.util.Deque;
import java.util.LinkedList;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 多租户上下文持有者
 * 维护当前线程租户上下文与租户忽略标志位的持有者，提供受控编程式闭包、SPI 自动回退与跨线程隔离能力
 *
 * @author Ateng
 * @since 2026-10-17
 */
public final class TenantContextHolder {

    /**
     * 当前线程租户 ID 栈（采用 LinkedList 支持 null 元素并支持嵌套 runWithTenant 安全回滚还原）
     */
    private static final ThreadLocal<Deque<String>> TENANT_ID_STACK = ThreadLocal.withInitial(LinkedList::new);

    /**
     * 当前线程忽略租户深度计数器（> 0 表示处于忽略状态，支持嵌套 runWithoutTenant 安全还原）
     */
    private static final ThreadLocal<Integer> IGNORE_DEPTH = ThreadLocal.withInitial(() -> 0);

    /**
     * 外部租户信息提取 SPI
     */
    private static volatile CurrentTenantProvider tenantProvider;

    private TenantContextHolder() {
    }

    /**
     * 注册或更新全局租户提取 SPI
     *
     * @param provider 租户提取提供者
     */
    public static void setTenantProvider(CurrentTenantProvider provider) {
        tenantProvider = provider;
    }

    /**
     * 获取当前全局配置的租户提取 SPI
     *
     * @return 当前租户 SPI
     */
    public static CurrentTenantProvider getTenantProvider() {
        return tenantProvider;
    }

    /**
     * 获取当前线程绑定的租户标识（字符串形式）
     * 优先从本地显式栈中提取，若栈为空则回退调用 {@link CurrentTenantProvider}
     *
     * @return 当前租户标识，若无则返回 null
     */
    public static String getTenantId() {
        Deque<String> stack = TENANT_ID_STACK.get();
        if (!stack.isEmpty()) {
            String tenantId = stack.peek();
            if (tenantId != null && !tenantId.isBlank()) {
                return tenantId;
            }
        }
        CurrentTenantProvider provider = tenantProvider;
        if (provider != null) {
            try {
                return provider.getCurrentTenantId();
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
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
        if (stack.isEmpty()) {
            if (tenantId != null) {
                stack.push(tenantId);
            }
        } else {
            stack.pop();
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
        Objects.requireNonNull(supplier, "supplier 不能为空");
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
        Objects.requireNonNull(runnable, "runnable 不能为空");
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
        Objects.requireNonNull(supplier, "supplier 不能为空");
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
        Objects.requireNonNull(runnable, "runnable 不能为空");
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

    /**
     * 抓取当前线程的多租户状态快照（包含租户栈与忽略深度）
     * 若当前栈为空但通过 SPI 能获取到租户 ID，则固化在快照中以确保跨线程不丢失
     *
     * @return 多租户状态快照
     */
    public static TenantSnapshot capture() {
        Deque<String> currentStack = TENANT_ID_STACK.get();
        Deque<String> stackCopy = new LinkedList<>(currentStack);
        if (stackCopy.isEmpty()) {
            String effectiveTenant = getTenantId();
            if (effectiveTenant != null && !effectiveTenant.isBlank()) {
                stackCopy.push(effectiveTenant);
            }
        }
        return new TenantSnapshot(stackCopy, IGNORE_DEPTH.get());
    }

    /**
     * 纯净克隆当前线程实际持有的多租户现场（仅包含当前线程显式持有的栈与深度，不触发 SPI 回退解析）
     * 专供异步工作线程在执行任务前暂存现场，并在任务完成后精准恢复
     *
     * @return 当前线程的原始租户现场快照
     */
    public static TenantSnapshot capturePrevious() {
        Deque<String> currentStack = TENANT_ID_STACK.get();
        return new TenantSnapshot(new LinkedList<>(currentStack), IGNORE_DEPTH.get());
    }

    /**
     * 将快照中的多租户状态恢复至当前工作线程
     *
     * @param snapshot 多租户状态快照
     */
    public static void restore(TenantSnapshot snapshot) {
        if (snapshot == null || (snapshot.tenantStack.isEmpty() && snapshot.ignoreDepth == 0)) {
            clear();
        } else {
            if (snapshot.tenantStack.isEmpty()) {
                TENANT_ID_STACK.remove();
            } else {
                TENANT_ID_STACK.set(new LinkedList<>(snapshot.tenantStack));
            }
            if (snapshot.ignoreDepth == 0) {
                IGNORE_DEPTH.remove();
            } else {
                IGNORE_DEPTH.set(snapshot.ignoreDepth);
            }
        }
    }

    /**
     * 多租户跨线程状态快照不可变数据结构
     */
    public static final class TenantSnapshot {
        private final Deque<String> tenantStack;
        private final int ignoreDepth;

        private TenantSnapshot(Deque<String> tenantStack, int ignoreDepth) {
            this.tenantStack = tenantStack != null ? tenantStack : new LinkedList<>();
            this.ignoreDepth = Math.max(0, ignoreDepth);
        }

        public String getTenantId() {
            return tenantStack.isEmpty() ? null : tenantStack.peek();
        }

        public boolean isIgnoreTenant() {
            return ignoreDepth > 0;
        }
    }
}
