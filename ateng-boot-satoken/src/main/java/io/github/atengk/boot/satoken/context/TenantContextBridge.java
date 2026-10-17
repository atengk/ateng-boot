package io.github.atengk.boot.satoken.context;

import io.github.atengk.boot.core.context.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;

/**
 * 多租户上下文安全协同桥接器
 * 将 Sa-Token 登录主体的租户标识协同绑定至 Core 的 TenantContextHolder，并在请求结束时统一清理
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
public final class TenantContextBridge {

    private TenantContextBridge() {
    }

    /**
     * 将登录主体的租户 ID 绑定至多租户上下文
     *
     * @param tenantId 租户唯一标识
     */
    public static void setTenantId(String tenantId) {
        if (tenantId != null && !tenantId.isBlank()) {
            TenantContextHolder.setTenantId(tenantId);
        }
    }

    /**
     * 清理当前线程的多租户上下文，彻底杜绝容器线程池复用污染
     */
    public static void clear() {
        TenantContextHolder.clear();
    }
}
