package io.github.atengk.boot.mybatisplus.entity;

import java.io.Serializable;

/**
 * 泛型多租户标识能力契约接口
 * 提供类型安全的租户标识读写契约，统一兼容 Long 与 String 两种租户主键类型
 *
 * @param <T> 租户标识类型（如 Long 或 String）
 * @author Ateng
 * @since 2026-11-20
 */
public interface TenantAware<T extends Serializable> {

    /**
     * 获取当前实体归属的租户标识
     *
     * @return 租户标识
     */
    T getTenantId();

    /**
     * 设置当前实体归属的租户标识
     *
     * @param tenantId 租户标识
     */
    void setTenantId(T tenantId);
}
