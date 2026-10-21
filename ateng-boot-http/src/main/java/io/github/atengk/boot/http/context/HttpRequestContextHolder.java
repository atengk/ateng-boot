package io.github.atengk.boot.http.context;

import java.time.Duration;

/**
 * HTTP 内部单次请求动态上下文持有者
 * 维护单次请求的特殊覆盖参数（如单次读取超时覆盖），并在底层物理连接工厂创建物理请求时直接感知应用，
 * 彻底规避在装配拦截器链场景下 InterceptingClientHttpRequest 延迟构建底层请求导致超时覆盖失效的问题
 *
 * @author Ateng
 * @since 2026-10-21
 */
public final class HttpRequestContextHolder {

    private static final ThreadLocal<Duration> READ_TIMEOUT_HOLDER = new ThreadLocal<>();

    private HttpRequestContextHolder() {
    }

    /**
     * 设置当前线程单次请求的读取超时时间
     *
     * @param timeout 单次读取超时时间
     */
    public static void setReadTimeout(Duration timeout) {
        if (timeout != null) {
            READ_TIMEOUT_HOLDER.set(timeout);
        } else {
            READ_TIMEOUT_HOLDER.remove();
        }
    }

    /**
     * 获取当前线程单次请求的读取超时时间
     *
     * @return 当前绑定的超时时间，若无则返回 null
     */
    public static Duration getReadTimeout() {
        return READ_TIMEOUT_HOLDER.get();
    }

    /**
     * 清理当前线程的所有单次上下文参数，防止线程复用污染
     */
    public static void clear() {
        READ_TIMEOUT_HOLDER.remove();
    }
}
