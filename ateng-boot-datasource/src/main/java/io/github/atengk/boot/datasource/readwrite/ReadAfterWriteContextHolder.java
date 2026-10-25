package io.github.atengk.boot.datasource.readwrite;

/**
 * 请求作用域写后读主防延迟上下文持有者 (REQUEST Scope)
 * 用于在单次调用/HTTP请求生命周期内跟踪是否发生过写操作
 *
 * @author Ateng
 * @since 2026-10-24
 */
public final class ReadAfterWriteContextHolder {

    private static final ThreadLocal<Boolean> WRITE_OCCURRED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private ReadAfterWriteContextHolder() {
    }

    /**
     * 标记当前请求/线程已发生写操作
     */
    public static void markWriteOccurred() {
        WRITE_OCCURRED.set(Boolean.TRUE);
    }

    /**
     * 判断当前请求/线程是否已发生写操作
     *
     * @return true 表示发生过写操作，后续只读查询需强制路由至主库；false 表示未发生写操作
     */
    public static boolean isWriteOccurred() {
        return Boolean.TRUE.equals(WRITE_OCCURRED.get());
    }

    /**
     * 清除当前线程写标记
     */
    public static void clear() {
        WRITE_OCCURRED.remove();
    }
}
