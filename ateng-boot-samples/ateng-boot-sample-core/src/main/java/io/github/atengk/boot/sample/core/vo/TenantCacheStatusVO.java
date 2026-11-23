package io.github.atengk.boot.sample.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 有界 LRU 缓存状态与淘汰演进视图对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantCacheStatusVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 初始缓存大小
     */
    private Integer initialSize;

    /**
     * 当前活跃读取的租户信息
     */
    private TenantQuotaVO activeTenant;

    /**
     * 容量超限被淘汰的租户编号
     */
    private String evictedTenantId;

    /**
     * 淘汰租户是否已被物理清理出缓存
     */
    private boolean evicted;

    /**
     * 通过原子计算 computeIfAbsent 初始化的租户信息
     */
    private TenantQuotaVO computedTenant;

    /**
     * 当前缓存条目总数
     */
    private Integer currentCacheSize;
}
