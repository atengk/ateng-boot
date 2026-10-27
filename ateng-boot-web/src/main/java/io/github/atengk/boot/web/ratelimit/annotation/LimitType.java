package io.github.atengk.boot.web.ratelimit.annotation;

/**
 * 接口限流防刷维度枚举
 *
 * @author Ateng
 * @since 2026-10-16
 */
public enum LimitType {

    /**
     * 接口方法级默认全局限流（按 Controller 方法或 URI 聚合）
     */
    DEFAULT,

    /**
     * 客户端真实 IP 维度限流（依托 IpUtil 提取客户端真实 IP）
     */
    IP,

    /**
     * 登录用户维度限流（依托 UserContextHolder 提取当前用户 ID）
     */
    USER,

    /**
     * 多租户维度限流（依托 TenantContextHolder 提取当前租户 ID，未设置时默认回退为 default）
     */
    TENANT
}
