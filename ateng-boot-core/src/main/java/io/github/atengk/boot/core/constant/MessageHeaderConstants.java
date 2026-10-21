package io.github.atengk.boot.core.constant;

/**
 * 跨中间件异步消息通信标准消息头常量
 * 规范全反应堆消息队列（RabbitMQ、Kafka、RocketMQ）出入站消息头契约，强制归一化为纯净 UTF-8 String
 *
 * @author Ateng
 * @since 2026-10-21
 */
public final class MessageHeaderConstants {

    private MessageHeaderConstants() {
    }

    /**
     * 全链路追踪 TraceId
     */
    public static final String TRACE_ID = "X-Trace-Id";

    /**
     * 多租户租户 ID
     */
    public static final String TENANT_ID = "X-Tenant-Id";

    /**
     * 操作人用户 ID
     */
    public static final String USER_ID = "X-User-Id";

    /**
     * 是否忽略租户过滤 (true/false)
     */
    public static final String IGNORE_TENANT = "X-Ignore-Tenant";

    /**
     * 消息发送端毫秒时间戳
     */
    public static final String SEND_TIMESTAMP = "X-Send-Timestamp";

    /**
     * 消息业务唯一标识 (供防重幂等与运维排障追溯)
     */
    public static final String BIZ_KEY = "X-Biz-Key";
}
