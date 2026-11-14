package io.github.atengk.boot.http.context;

import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * HTTP 内部单次请求动态上下文持有者
 * 维护单次请求的特殊覆盖参数（如单次读取超时、建联超时覆盖及逻辑客户端名称），并在底层物理连接工厂及拦截器链中直接感知应用，
 * 彻底规避在装配拦截器链场景下 InterceptingClientHttpRequest 延迟构建底层请求导致超时覆盖失效及跨拦截器上下文丢失的问题
 *
 * @author Ateng
 * @since 2026-10-21
 */
public final class HttpRequestContextHolder {

    private static final ThreadLocal<Duration> READ_TIMEOUT_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<Duration> CONNECT_TIMEOUT_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> CLIENT_NAME_HOLDER = new ThreadLocal<>();

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
     * @return 当前绑定的读取超时时间，若无则返回 null
     */
    public static Duration getReadTimeout() {
        return READ_TIMEOUT_HOLDER.get();
    }

    /**
     * 设置当前线程单次请求的建联超时时间
     *
     * @param timeout 单次建联超时时间
     */
    public static void setConnectTimeout(Duration timeout) {
        if (timeout != null) {
            CONNECT_TIMEOUT_HOLDER.set(timeout);
        } else {
            CONNECT_TIMEOUT_HOLDER.remove();
        }
    }

    /**
     * 获取当前线程单次请求的建联超时时间
     *
     * @return 当前绑定的建联超时时间，若无则返回 null
     */
    public static Duration getConnectTimeout() {
        return CONNECT_TIMEOUT_HOLDER.get();
    }

    /**
     * 设置当前线程单次请求的逻辑客户端名称
     *
     * @param clientName 逻辑客户端名称（为空或空白字符时等价移除）
     */
    public static void setClientName(String clientName) {
        if (StringUtils.hasText(clientName)) {
            CLIENT_NAME_HOLDER.set(clientName.trim());
        } else {
            CLIENT_NAME_HOLDER.remove();
        }
    }

    /**
     * 获取当前线程单次请求的逻辑客户端名称
     *
     * @return 当前绑定的逻辑客户端名称，若无则返回 null
     */
    public static String getClientName() {
        return CLIENT_NAME_HOLDER.get();
    }

    /**
     * 清理当前线程的所有单次上下文参数，防止线程复用污染
     */
    public static void clear() {
        READ_TIMEOUT_HOLDER.remove();
        CONNECT_TIMEOUT_HOLDER.remove();
        CLIENT_NAME_HOLDER.remove();
    }
}
