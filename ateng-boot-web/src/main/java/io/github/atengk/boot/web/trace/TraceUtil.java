package io.github.atengk.boot.web.trace;

import org.slf4j.MDC;

/**
 * 全链路追踪门面工具类
 * 提供 MDC traceId 的提取、注入与清理便捷门面
 *
 * @author Ateng
 * @since 2026-10-17
 */
public final class TraceUtil {

    private TraceUtil() {
    }

    /**
     * 获取当前线程绑定的链路追踪标识 traceId
     *
     * @return 当前链路追踪标识，未绑定时返回 null
     */
    public static String getTraceId() {
        return MDC.get(TraceFilter.MDC_TRACE_ID_KEY);
    }

    /**
     * 为当前线程绑定指定的 traceId
     *
     * @param traceId 链路追踪标识
     */
    public static void setTraceId(String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(TraceFilter.MDC_TRACE_ID_KEY, traceId);
        } else {
            clear();
        }
    }

    /**
     * 清理当前线程绑定的 traceId
     */
    public static void clear() {
        MDC.remove(TraceFilter.MDC_TRACE_ID_KEY);
    }
}
