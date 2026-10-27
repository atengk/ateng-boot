package io.github.atengk.boot.mybatisplus.context;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 逻辑删除穿透与全量检索上下文持有者
 * 依托 ThreadLocal 嵌套计数器实现编程式逻辑删除过滤剥离闭包，严格遵守上下文生命周期确定性清理铁律
 *
 * @author Ateng
 * @since 2026-10-27
 */
public final class LogicDeleteContextHolder {

    private static final ThreadLocal<Integer> NESTED_LEVEL = ThreadLocal.withInitial(() -> 0);

    private LogicDeleteContextHolder() {
    }

    /**
     * 判断当前调用线程是否处于逻辑删除穿透模式
     *
     * @return true 若当前处于穿透模式（即需包含已逻辑删除记录）；否则 false
     */
    public static boolean isWithDeleted() {
        return NESTED_LEVEL.get() > 0;
    }

    /**
     * 在逻辑删除穿透模式下执行指定无返回值业务闭包
     * 自动处理嵌套调用并保证在 finally 块中确定性恢复现场
     *
     * @param runnable 待执行业务逻辑（不能为 null）
     */
    public static void runWithDeleted(Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable 不能为 null");
        startWithDeleted();
        try {
            runnable.run();
        } finally {
            stopWithDeleted();
        }
    }

    /**
     * 在逻辑删除穿透模式下执行指定有返回值业务闭包
     * 自动处理嵌套调用并保证在 finally 块中确定性恢复现场
     *
     * @param supplier 待执行业务逻辑（不能为 null）
     * @param <R>      返回值泛型
     * @return 业务逻辑执行结果
     */
    public static <R> R callWithDeleted(Supplier<R> supplier) {
        Objects.requireNonNull(supplier, "supplier 不能为 null");
        startWithDeleted();
        try {
            return supplier.get();
        } finally {
            stopWithDeleted();
        }
    }

    /**
     * 手动清理当前线程上下文（主要用于线程池复用防御与测试套件现场重置）
     */
    public static void clear() {
        NESTED_LEVEL.remove();
    }

    private static void startWithDeleted() {
        NESTED_LEVEL.set(NESTED_LEVEL.get() + 1);
    }

    private static void stopWithDeleted() {
        int level = NESTED_LEVEL.get() - 1;
        if (level <= 0) {
            NESTED_LEVEL.remove();
        } else {
            NESTED_LEVEL.set(level);
        }
    }
}
