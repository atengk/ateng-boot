package io.github.atengk.boot.mail.provider;

import io.github.atengk.boot.mail.model.MailAccount;

/**
 * 邮件服务 OAuth2 XOAUTH2 访问令牌动态供给与刷新扩展接口 (SPI)
 * <p>
 * 面向 Microsoft 365、Google Workspace 等现代邮箱网关提供短期 Access Token 获取与认证失败自动刷新能力。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-16
 */
@FunctionalInterface
public interface MailAccessTokenProvider {

    /**
     * 获取指定发信账号当前有效的 OAuth2 访问令牌
     *
     * @param account 发信账号模型
     * @return OAuth2 Access Token，未获取到时可返回 null
     */
    String getAccessToken(MailAccount account);

    /**
     * 当首次使用令牌遇到 SMTP 认证失败时回调刷新访问令牌（默认委托 {@link #getAccessToken(MailAccount)}）
     *
     * @param account 发信账号模型
     * @return 刷新后的最新 OAuth2 Access Token
     */
    default String refreshAccessToken(MailAccount account) {
        return getAccessToken(account);
    }
}
