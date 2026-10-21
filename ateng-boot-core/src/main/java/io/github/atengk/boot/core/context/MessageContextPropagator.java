package io.github.atengk.boot.core.context;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.util.IdUtil;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 跨中间件消息四维上下文透传与生命周期防卫门面
 * 负责在出站消息时捕获 MDC TraceId、User 身份、Tenant 租户与 IgnoreTenant 标志位并注入消息头 (UTF-8 String 归一化)；
 * 在消费入站时还原当前线程上下文现场，并通过返回的 {@link ContextScope} 在 finally 块中绝对可靠地恢复原始现场，杜绝线程污染
 *
 * @author Ateng
 * @since 2026-10-21
 */
public final class MessageContextPropagator {

    /**
     * MDC 中 TraceId 的标准属性名
     */
    public static final String MDC_TRACE_ID_KEY = "traceId";

    private MessageContextPropagator() {
    }

    /**
     * 抓取当前调用线程四维上下文，注入至目标消息头 Map
     * 自动补齐时间戳，所有键值 100% 显式归一化为 UTF-8 String
     *
     * @param headers 目标消息头 Map
     */
    public static void inject(Map<String, Object> headers) {
        inject(headers, null);
    }

    /**
     * 抓取当前调用线程四维上下文及业务标识，注入至目标消息头 Map
     * 若未提供 bizKey，则尝试保留已有 bizKey，若仍无则自动采用 traceId 兜底，保障 bizKey 绝对非空
     *
     * @param headers 目标消息头 Map
     * @param bizKey  业务唯一标识 (可选)
     */
    public static void inject(Map<String, Object> headers, String bizKey) {
        if (headers == null) {
            return;
        }

        // 1. TraceId (若当前无 traceId，则生成纯原生 fastSimpleUUID 注入)
        String traceId = MDC.get(MDC_TRACE_ID_KEY);
        if (traceId == null || traceId.isBlank()) {
            traceId = IdUtil.fastSimpleUUID();
        }
        headers.put(MessageHeaderConstants.TRACE_ID, traceId);

        // 2. TenantId (若非空则注入字符串形式)
        String tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null && !tenantId.isBlank()) {
            headers.put(MessageHeaderConstants.TENANT_ID, tenantId);
        }

        // 3. UserId (若非空则注入字符串形式)
        String userId = UserContextHolder.get();
        if (userId != null && !userId.isBlank()) {
            headers.put(MessageHeaderConstants.USER_ID, userId);
        }

        // 4. IgnoreTenant
        if (TenantContextHolder.isIgnoreTenant()) {
            headers.put(MessageHeaderConstants.IGNORE_TENANT, "true");
        }

        // 5. SendTimestamp
        headers.put(MessageHeaderConstants.SEND_TIMESTAMP, String.valueOf(System.currentTimeMillis()));

        // 6. BizKey 三级自适应保底提取
        String effectiveBizKey = bizKey;
        if (effectiveBizKey == null || effectiveBizKey.isBlank()) {
            Object existingBizKey = headers.get(MessageHeaderConstants.BIZ_KEY);
            if (existingBizKey != null) {
                effectiveBizKey = String.valueOf(existingBizKey);
            }
        }
        if (effectiveBizKey == null || effectiveBizKey.isBlank()) {
            effectiveBizKey = traceId;
        }
        headers.put(MessageHeaderConstants.BIZ_KEY, effectiveBizKey);
    }

    /**
     * 从入站消息头提取四维上下文并在当前线程安全激活，返回现场自恢复清理句柄
     *
     * @param headers 入站消息头 Map (支持兼容 Object、byte[] 等异构 MQ 头格式)
     * @return 现场恢复句柄 ContextScope，必须在 try-with-resources 或 finally 块中 close()
     */
    public static ContextScope activate(Map<String, ?> headers) {
        // 1. 抓取工作线程执行前的原始现场
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        String previousUser = UserContextHolder.getExplicit();
        TenantContextHolder.TenantSnapshot previousTenant = TenantContextHolder.capturePrevious();

        // 2. 从消息头中提取四维上下文
        String traceId = extractString(headers, MessageHeaderConstants.TRACE_ID);
        String tenantId = extractString(headers, MessageHeaderConstants.TENANT_ID);
        String userId = extractString(headers, MessageHeaderConstants.USER_ID);
        String ignoreTenant = extractString(headers, MessageHeaderConstants.IGNORE_TENANT);

        // 3. 激活 MDC TraceId (隔离当前现场，若消息未携带 traceId，则生成新 ID 保证链路可追踪)
        MDC.clear();
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(MDC_TRACE_ID_KEY, traceId);
        } else {
            MDC.put(MDC_TRACE_ID_KEY, IdUtil.fastSimpleUUID());
        }

        // 4. 激活 Tenant 上下文 (先清空当前线程旧现场，防止入站消息未携带租户头时发生脏读逃逸)
        TenantContextHolder.clear();
        if (tenantId != null && !tenantId.isBlank()) {
            TenantContextHolder.setTenantId(tenantId);
        }
        if ("true".equalsIgnoreCase(ignoreTenant)) {
            TenantContextHolder.setIgnoreTenant(true);
        }

        // 5. 激活 User 上下文 (显式设置或清空当前线程旧现场)
        if (userId != null && !userId.isBlank()) {
            UserContextHolder.set(userId);
        } else {
            UserContextHolder.clear();
        }

        // 6. 返回具备确定性自恢复能力的句柄
        return () -> {
            try {
                if (previousMdc != null && !previousMdc.isEmpty()) {
                    MDC.setContextMap(previousMdc);
                } else {
                    MDC.clear();
                }
            } finally {
                try {
                    if (previousUser != null) {
                        UserContextHolder.set(previousUser);
                    } else {
                        UserContextHolder.clear();
                    }
                } finally {
                    TenantContextHolder.restore(previousTenant);
                }
            }
        };
    }

    /**
     * 在入站消息上下文中安全闭包式执行 Runnable 任务，完成后自动绝对可靠清理现场
     *
     * @param headers 入站消息头 Map
     * @param task    业务任务
     */
    public static void runInContext(Map<String, ?> headers, Runnable task) {
        if (task == null) {
            return;
        }
        try (ContextScope ignored = activate(headers)) {
            task.run();
        }
    }

    /**
     * 在入站消息上下文中安全闭包式执行 Callable 任务并获取返回值，完成后自动绝对可靠清理现场
     *
     * @param headers  入站消息头 Map
     * @param callable 业务任务
     * @param <V>      返回值泛型
     * @return 业务返回值
     * @throws Exception 任务执行异常
     */
    public static <V> V callInContext(Map<String, ?> headers, Callable<V> callable) throws Exception {
        if (callable == null) {
            return null;
        }
        try (ContextScope ignored = activate(headers)) {
            return callable.call();
        }
    }

    /**
     * 宽容提取消息头并安全转换为 UTF-8 字符串
     * 兼容 RabbitMQ(String/Object)、Kafka(byte[]) 等不同驱动格式
     *
     * @param headers 消息头
     * @param key     键名
     * @return 字符串值，不存在或为空时返回 null
     */
    public static String extractString(Map<String, ?> headers, String key) {
        if (headers == null || key == null) {
            return null;
        }
        Object val = headers.get(key);
        if (val == null) {
            return null;
        }
        if (val instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (val instanceof String str) {
            return str;
        }
        return String.valueOf(val);
    }

    /**
     * 上下文作用域清理句柄，继承 AutoCloseable 以支持 try-with-resources
     */
    @FunctionalInterface
    public interface ContextScope extends AutoCloseable {
        /**
         * 恢复现场并释放资源，重写免除 throws Exception
         */
        @Override
        void close();
    }
}
