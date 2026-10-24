package io.github.atengk.boot.sample.datasource.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 订单展示视图对象
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderResponse {

    /**
     * 订单主键 ID
     */
    private Long id;

    /**
     * 业务订单号
     */
    private String orderNo;

    /**
     * 归属租户标识
     */
    private String tenantId;

    /**
     * 商品名称
     */
    private String productName;

    /**
     * 订单总金额 (元)
     */
    private BigDecimal amount;

    /**
     * 订单状态
     */
    private String status;

    /**
     * 实际查询路由的数据源标识 (用于自验观测)
     */
    private String routedDataSource;
}
