package io.github.atengk.boot.satoken.cluster.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Sa-Token 分布式集群同步广播事件类型枚举
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Getter
@RequiredArgsConstructor
public enum SaTokenClusterEventType {

    /**
     * 用户注销下线
     */
    LOGOUT("用户注销"),

    /**
     * 账号被踢下线
     */
    KICKOUT("踢人下线"),

    /**
     * 账号顶替下线
     */
    REPLACED("顶替下线"),

    /**
     * 账号/服务封禁
     */
    DISABLE("账号封禁"),

    /**
     * 账号解封
     */
    UNTIE_DISABLE("账号解封"),

    /**
     * 用户角色/权限即时刷新
     */
    USER_PERMISSION_REFRESH("用户权限刷新"),

    /**
     * 角色权限即时刷新
     */
    ROLE_PERMISSION_REFRESH("角色权限刷新"),

    /**
     * 全量权限缓存清空
     */
    ALL_CACHE_CLEAR("全量缓存清空");

    private final String description;
}
