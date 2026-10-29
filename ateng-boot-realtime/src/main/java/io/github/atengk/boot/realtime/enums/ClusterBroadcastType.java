package io.github.atengk.boot.realtime.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 集群分布式广播消息目标类型枚举
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Getter
@RequiredArgsConstructor
public enum ClusterBroadcastType {

    /**
     * 指定用户单播（广播至所有节点在本地检索该用户并下发）
     */
    USER("user", "指定用户单播"),

    /**
     * 房间/频道群组组播（广播至所有节点在本地检索该群组成员并下发）
     */
    GROUP("group", "房间频道组播"),

    /**
     * 指定多租户广播（广播至所有节点在本地检索该租户会话并下发）
     */
    TENANT("tenant", "多租户隔离广播"),

    /**
     * 全服全员广播（广播至所有节点全量活跃会话下发）
     */
    ALL("all", "全服全员广播"),

    /**
     * 强制剔除用户下线信令（广播至所有节点强制关闭该用户的全部连接）
     */
    KICKOUT("kickout", "强制踢人下线信令"),

    /**
     * 解散群组房间信令（广播至所有节点在本地解散该群组并通知成员）
     */
    DISMISS_GROUP("dismiss_group", "解散群组房间信令"),

    /**
     * 强制剔除租户全部会话下线信令（广播至所有节点强制关闭该租户的全部连接）
     */
    KICKOUT_TENANT("kickout_tenant", "强制剔除租户下线信令");

    private final String code;
    private final String description;
}
