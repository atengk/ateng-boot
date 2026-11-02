package io.github.atengk.boot.sms.registry;

import io.github.atengk.boot.core.context.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;

/**
 * 基于多租户上下文的默认短信渠道解析器
 * 自动桥接 TenantContextHolder 提取当前租户绑定的短信渠道名称
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
public class TenantSmsChannelResolver implements SmsChannelResolver {

    private final SmsChannelRegistry channelRegistry;

    public TenantSmsChannelResolver(SmsChannelRegistry channelRegistry) {
        this.channelRegistry = channelRegistry;
    }

    @Override
    public String resolveChannel() {
        String tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return null;
        }

        String trimmedTenant = tenantId.trim();

        // 1. 优先匹配 tenant-{tenantId} 命名规范渠道
        String candidateTenantPrefix = "tenant-" + trimmedTenant;
        if (channelRegistry != null && channelRegistry.contains(candidateTenantPrefix)) {
            if (log.isDebugEnabled()) {
                log.debug("[TenantSmsChannelResolver] 命中租户专属短信渠道 [{}] (租户ID: {})", candidateTenantPrefix, trimmedTenant);
            }
            return candidateTenantPrefix;
        }

        // 2. 匹配直接以 tenantId 命名的渠道
        if (channelRegistry != null && channelRegistry.contains(trimmedTenant)) {
            if (log.isDebugEnabled()) {
                log.debug("[TenantSmsChannelResolver] 命中租户同名短信渠道 [{}] (租户ID: {})", trimmedTenant, trimmedTenant);
            }
            return trimmedTenant;
        }

        return null;
    }

    @Override
    public int getOrder() {
        return 100;
    }
}
