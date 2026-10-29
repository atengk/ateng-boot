package io.github.atengk.boot.realtime.model;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.util.IdUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.MDC;

import java.io.Serial;
import java.io.Serializable;

/**
 * 跨通道统一数据下发强类型契约载荷
 * <p>
 * 规范化封装消息唯一编号（id）、业务事件名（event）、泛型数据（data）、发送时间戳（timestamp）与全链路追踪 TraceId。
 * </p>
 *
 * @param <T> 业务载荷泛型类型
 * @author Ateng
 * @since 2026-10-21
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RealtimeMessage<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 常用事件名常量：心跳 Ping
     */
    public static final String EVENT_PING = "ping";

    /**
     * 常用事件名常量：心跳 Pong
     */
    public static final String EVENT_PONG = "pong";

    /**
     * 常用事件名常量：系统公告/通知
     */
    public static final String EVENT_NOTICE = "notice";

    /**
     * 常用事件名常量：错误通知
     */
    public static final String EVENT_ERROR = "error";

    /**
     * 常用事件名常量：流式数据 Chunk (打字机推流)
     */
    public static final String EVENT_STREAM_CHUNK = "stream:chunk";

    /**
     * 常用事件名常量：流式数据结束标记
     */
    public static final String EVENT_STREAM_DONE = "stream:done";

    /**
     * 消息唯一编号（基于 Snowflake 分布式主键或短 UUID 生成）
     */
    private String id;

    /**
     * 业务事件标识（如 ping, pong, notice, chat:message, task:progress）
     */
    private String event;

    /**
     * 业务载荷数据
     */
    private T data;

    /**
     * 消息生成或发送时间戳（毫秒级 Epoch 纪元时间）
     */
    private Long timestamp;

    /**
     * 当前全链路追踪 TraceId（自动提取当前 MDC 上下文，无上下文时自动生成短 UUID 兜底）
     */
    private String traceId;

    /**
     * 多租户标识（自动绑定当前租户上下文）
     */
    private String tenantId;

    /**
     * 是否需要离线持久化暂存（当目标用户离线无活跃连接时触发离线存储 SPI）
     */
    private Boolean persistent;

    /**
     * 快速构建业务事件消息（自动填充 id, timestamp, traceId, tenantId）
     *
     * @param event 业务事件标识
     * @param data  业务数据
     * @param <T>   数据泛型
     * @return 实时消息载荷实例
     */
    public static <T> RealtimeMessage<T> of(String event, T data) {
        return of(IdUtil.nextIdStr(), event, data);
    }

    /**
     * 快速构建持久化离线消息（当用户离线时触发离线消息暂存 SPI）
     *
     * @param event 业务事件标识
     * @param data  业务数据
     * @param <T>   数据泛型
     * @return 标记持久化的实时消息载荷实例
     */
    public static <T> RealtimeMessage<T> ofPersistent(String event, T data) {
        RealtimeMessage<T> message = of(event, data);
        message.setPersistent(true);
        return message;
    }

    /**
     * 显式指定消息 ID 构建业务事件消息（自动填充 timestamp, traceId, tenantId）
     *
     * @param id    消息唯一编号
     * @param event 业务事件标识
     * @param data  业务数据
     * @param <T>   数据泛型
     * @return 实时消息载荷实例
     */
    public static <T> RealtimeMessage<T> of(String id, String event, T data) {
        String traceId = resolveTraceId();
        String tenantId = resolveTenantId();
        return RealtimeMessage.<T>builder()
                .id(id != null ? id : IdUtil.nextIdStr())
                .event(event)
                .data(data)
                .timestamp(System.currentTimeMillis())
                .traceId(traceId)
                .tenantId(tenantId)
                .build();
    }

    /**
     * 快速构建 Ping 心跳消息
     *
     * @return Ping 实时消息
     */
    public static RealtimeMessage<String> ping() {
        return of(EVENT_PING, "ping");
    }

    /**
     * 快速构建 Pong 心跳响应消息
     *
     * @return Pong 实时消息
     */
    public static RealtimeMessage<String> pong() {
        return of(EVENT_PONG, "pong");
    }

    /**
     * 解析当前线程 MDC 中的 traceId，缺失时生成短 UUID 保底
     *
     * @return traceId
     */
    private static String resolveTraceId() {
        String traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            return traceId;
        }
        return IdUtil.fastSimpleUUID();
    }

    /**
     * 解析当前线程的租户标识
     *
     * @return tenantId
     */
    private static String resolveTenantId() {
        try {
            return TenantContextHolder.getTenantId();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
