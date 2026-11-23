package io.github.atengk.boot.sample.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 租户配额缓存视图对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantQuotaVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 租户编号
     */
    private String tenantId;

    /**
     * 租户名称
     */
    private String tenantName;

    /**
     * 配额上限
     */
    private Integer quotaLimit;

    /**
     * 已使用配额
     */
    private Integer usedQuota;
}
