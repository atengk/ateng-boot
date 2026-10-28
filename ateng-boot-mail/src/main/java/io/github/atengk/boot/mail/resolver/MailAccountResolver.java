package io.github.atengk.boot.mail.resolver;

import io.github.atengk.boot.mail.model.MailMessage;

/**
 * 发件账号动态路由解析器 SPI
 * <p>
 * 支持依据多租户上下文（{@link io.github.atengk.boot.core.context.TenantContextHolder}）
 * 或自定义业务标签动态解析发信账号标识。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@FunctionalInterface
public interface MailAccountResolver {

    /**
     * 动态解析当前发信应使用的发件账号名称
     *
     * @param message 待发信消息模型（可包含自定义标头或已指定的账号）
     * @return 匹配的发件账号逻辑名称；若返回 null 或空字符串，则框架将自动平滑回退至默认账号
     */
    String resolveAccount(MailMessage message);
}
