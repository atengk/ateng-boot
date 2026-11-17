package io.github.atengk.boot.sample.flyway.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * SaaS 租户订单响应视图对象（Zero-Entity-Leakage）
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantOrderResponse {

    private Long id;
    private String orderNo;
    private String tenantId;
    private String sourceDs;
    private String productName;
    private BigDecimal amount;
    private String status;
    private String routedDataSource;
}
