package io.github.atengk.boot.web.ip;

/**
 * IP 访问控制校验模式枚举
 * 定义白名单（WHITE_LIST，仅允许列表内 IP 访问）与黑名单（BLACK_LIST，拒绝列表内 IP 访问）策略
 *
 * @author Ateng
 * @since 2026-10-27
 */
public enum IpAccessMode {

    /**
     * 白名单模式：仅允许规则清单内的 IP 或 CIDR 网段访问，未命中则拒绝
     */
    WHITE_LIST,

    /**
     * 黑名单模式：拒绝规则清单内的 IP 或 CIDR 网段访问，未命中则放行
     */
    BLACK_LIST
}
