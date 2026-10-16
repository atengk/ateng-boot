package io.github.atengk.boot.mybatisplus.entity;

/**
 * 多租户实体标记接口
 * 提供类型安全的租户标识读写契约
 *
 * @author Ateng
 * @since 2026-10-16
 */
public interface TenantEntity {

    /**
     * 获取当前实体绑定的租户标识
     *
     * @return 租户标识
     */
    String getTenantId();

    /**
     * 设置当前实体的租户标识
     *
     * @param tenantId 租户标识
     */
    void setTenantId(String tenantId);
}
