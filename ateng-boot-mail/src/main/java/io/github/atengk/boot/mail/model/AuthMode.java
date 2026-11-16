package io.github.atengk.boot.mail.model;

import io.github.atengk.boot.core.enums.IBaseEnum;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * SMTP 邮件传输认证模式强类型枚举
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Getter
@RequiredArgsConstructor
public enum AuthMode implements IBaseEnum<String> {

    /**
     * 智能自适应：存在用户名/密码时启用 PASSWORD 认证，凭据为空时自动切换免认证中继
     */
    AUTO("智能自适应"),

    /**
     * 账号密码 / 授权码认证（强制启用 mail.smtp.auth=true）
     */
    PASSWORD("账号密码认证"),

    /**
     * 免认证内网 SMTP 中继（强制关闭认证并清空默认凭据）
     */
    NONE("免认证中继"),

    /**
     * OAuth2 XOAUTH2 访问令牌认证（适配 Microsoft 365 / Google Workspace 等现代网关）
     */
    XOAUTH2("XOAUTH2 令牌认证");

    /**
     * 认证模式中文描述
     */
    private final String label;

    @Override
    public String getCode() {
        return name();
    }
}
