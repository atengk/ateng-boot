package io.github.atengk.boot.openapi.properties;

/**
 * OpenAPI 接口文档访问守卫模式枚举
 *
 * @author Ateng
 * @since 2026-10-22
 */
public enum GuardMode {

    /**
     * 免密公开访问（不拦截）
     */
    NONE,

    /**
     * HTTP Basic 认证守卫（利用浏览器原生弹窗输入用户名密码）
     */
    BASIC,

    /**
     * Sa-Token 分布式会话与权限守卫
     */
    SATOKEN,

    /**
     * 纯 CIDR / IP 白名单网络防火墙守卫（仅允许白名单内网或指定网段访问）
     */
    IP_WHITELIST,

    /**
     * 静态令牌标头守卫（支持 X-Doc-Token 标头或可选 URL Query 参数比对，适合 CI/CD 与自动化调用）
     */
    TOKEN
}
