package io.github.atengk.boot.mail.resolver;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.mail.model.MailMessage;

/**
 * 基于多租户上下文的默认发件账号解析器
 * <p>
 * 遵循显式指定优先原则，若消息模型未显式指定发信账号，则自动联动
 * {@link TenantContextHolder#getTenantId()} 解析租户专属发件账号；
 * 当租户上下文为空或未命中时，返回 {@code null} 以便框架安全平滑回退至全局默认账号。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
public class TenantMailAccountResolver implements MailAccountResolver {

    @Override
    public String resolveAccount(MailMessage message) {
        if (message != null && message.getAccount() != null && !message.getAccount().isBlank()) {
            return message.getAccount();
        }
        String tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null && !tenantId.isBlank()) {
            return tenantId;
        }
        return null;
    }
}
