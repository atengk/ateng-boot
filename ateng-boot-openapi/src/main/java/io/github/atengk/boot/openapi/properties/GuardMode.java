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
    SATOKEN
}
