package io.github.atengk.boot.satoken.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.ClassUtils;

/**
 * 多租户上下文安全协同桥接器
 * 当存在 ateng-boot-mybatis-plus 依赖时，自动桥接 TenantContextHolder 生命周期，未引入时优雅静默降级
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
public final class TenantContextBridge {

    private static final boolean HAS_TENANT_HOLDER = ClassUtils.isPresent(
            "io.github.atengk.boot.mybatisplus.context.TenantContextHolder",
            TenantContextBridge.class.getClassLoader()
    );

    private TenantContextBridge() {
    }

    /**
     * 将登录主体的租户 ID 绑定至多租户上下文
     *
     * @param tenantId 租户唯一标识
     */
    public static void setTenantId(String tenantId) {
        if (HAS_TENANT_HOLDER && tenantId != null && !tenantId.isBlank()) {
            TenantContextHolderHelper.setTenantId(tenantId);
        }
    }

    /**
     * 清理当前线程的多租户上下文，彻底杜绝容器线程池复用污染
     */
    public static void clear() {
        if (HAS_TENANT_HOLDER) {
            TenantContextHolderHelper.clear();
        }
    }

    /**
     * 内部延迟加载静态内部类，避免在没有 mybatis-plus 依赖时触发类加载异常
     */
    private static class TenantContextHolderHelper {
        static void setTenantId(String tenantId) {
            io.github.atengk.boot.mybatisplus.context.TenantContextHolder.setTenantId(tenantId);
        }

        static void clear() {
            io.github.atengk.boot.mybatisplus.context.TenantContextHolder.clear();
        }
    }
}
