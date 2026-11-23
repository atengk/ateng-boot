package io.github.atengk.boot.redis.util;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import org.slf4j.MDC;

/**
 * Redis 异步消费线程四维上下文安全清理工具类
 * 统一封装 MDC 链路追踪、租户上下文（TenantId / IgnoreTenant）与用户身份上下文的确定性隔离清理逻辑，
 * 通过嵌套 try-finally 防御单维度清理异常阻断其余上下文清理，杜绝受管线程池复用造成的跨任务上下文污染。
 *
 * @author Ateng
 * @since 2026-11-13
 */
public final class RedisConsumptionContext {

    /**
     * MDC 中链路追踪标识的标准属性名
     */
    public static final String MDC_TRACE_ID_KEY = "traceId";

    private RedisConsumptionContext() {
    }

    /**
     * 完整清理当前消费线程的四维上下文（全量 MDC、租户上下文与操作人上下文）
     */
    public static void clearAll() {
        try {
            MDC.clear();
        } finally {
            try {
                TenantContextHolder.clear();
            } finally {
                UserContextHolder.clear();
            }
        }
    }
}
