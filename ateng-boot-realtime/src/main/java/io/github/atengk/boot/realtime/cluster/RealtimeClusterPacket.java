package io.github.atengk.boot.realtime.cluster;

import io.github.atengk.boot.realtime.enums.ClusterBroadcastType;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 集群分布式广播数据包契约载荷
 * <p>
 * 统一承载跨节点广播的事件类型、路由目标、业务消息及踢人信令，
 * 内置 {@code sourceNodeId} 以支持自发自收防反射过滤。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RealtimeClusterPacket implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 发送方源节点唯一标识（用于自发自收防反射过滤）
     */
    private String sourceNodeId;

    /**
     * 集群广播类型（USER / GROUP / TENANT / ALL / KICKOUT）
     */
    private ClusterBroadcastType broadcastType;

    /**
     * 目标路由键（根据 broadcastType 分别为 userId、groupId 或 tenantId）
     */
    private String targetKey;

    /**
     * 关联的租户标识（用于多租户单播隔离过滤，可选）
     */
    private Long tenantId;

    /**
     * 实时业务消息载荷（踢人信令时可为空）
     */
    private RealtimeMessage<?> message;

    /**
     * 踢人下线时的关闭状态码（如 4002）
     */
    private Integer closeStatusCode;

    /**
     * 踢人下线原因说明
     */
    private String reason;

    /**
     * 广播发送时间戳（毫秒）
     */
    @Builder.Default
    private long timestamp = System.currentTimeMillis();

    /**
     * 构建指定用户单播广播包
     */
    public static RealtimeClusterPacket ofUser(String sourceNodeId, Object userId, RealtimeMessage<?> message) {
        return ofUser(sourceNodeId, userId, null, message);
    }

    /**
     * 构建指定租户下指定用户单播广播包
     */
    public static RealtimeClusterPacket ofUser(String sourceNodeId, Object userId, Long tenantId, RealtimeMessage<?> message) {
        return RealtimeClusterPacket.builder()
                .sourceNodeId(sourceNodeId)
                .broadcastType(ClusterBroadcastType.USER)
                .targetKey(userId != null ? String.valueOf(userId) : null)
                .tenantId(tenantId)
                .message(message)
                .build();
    }

    /**
     * 构建房间/频道群组组播广播包
     */
    public static RealtimeClusterPacket ofGroup(String sourceNodeId, String groupId, RealtimeMessage<?> message) {
        return RealtimeClusterPacket.builder()
                .sourceNodeId(sourceNodeId)
                .broadcastType(ClusterBroadcastType.GROUP)
                .targetKey(groupId)
                .message(message)
                .build();
    }

    /**
     * 构建指定租户隔离广播包
     */
    public static RealtimeClusterPacket ofTenant(String sourceNodeId, Long tenantId, RealtimeMessage<?> message) {
        return RealtimeClusterPacket.builder()
                .sourceNodeId(sourceNodeId)
                .broadcastType(ClusterBroadcastType.TENANT)
                .targetKey(tenantId != null ? String.valueOf(tenantId) : null)
                .tenantId(tenantId)
                .message(message)
                .build();
    }

    /**
     * 构建全网全员广播包
     */
    public static RealtimeClusterPacket ofAll(String sourceNodeId, RealtimeMessage<?> message) {
        return RealtimeClusterPacket.builder()
                .sourceNodeId(sourceNodeId)
                .broadcastType(ClusterBroadcastType.ALL)
                .message(message)
                .build();
    }

    /**
     * 构建踢人下线信令广播包
     */
    public static RealtimeClusterPacket ofKickout(String sourceNodeId, Object userId, Integer closeStatusCode, String reason) {
        return RealtimeClusterPacket.builder()
                .sourceNodeId(sourceNodeId)
                .broadcastType(ClusterBroadcastType.KICKOUT)
                .targetKey(userId != null ? String.valueOf(userId) : null)
                .closeStatusCode(closeStatusCode)
                .reason(reason)
                .build();
    }
}
