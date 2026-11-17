package io.github.atengk.boot.sample.flyway.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 动态挂载物理租户库与自动迁移结果视图对象
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantMountResponse {

    private String tenantId;
    private String dsKey;
    private boolean mounted;
    private String currentVersion;
    private int appliedCount;
    private int initialOrderCount;
}
