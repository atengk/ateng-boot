package io.github.atengk.boot.sample.flyway.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 平台租户注册信息响应视图对象（Zero-Entity-Leakage）
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantRegistryResponse {

    private Long id;
    private String tenantCode;
    private String tenantName;
    private String dsKey;
    private String planTier;
    private String sourceDs;
    private String status;
}
