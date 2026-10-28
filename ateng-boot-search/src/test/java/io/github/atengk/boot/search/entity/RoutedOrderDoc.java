package io.github.atengk.boot.search.entity;

import io.github.atengk.boot.search.annotation.SearchId;
import io.github.atengk.boot.search.annotation.SearchIndex;
import io.github.atengk.boot.search.annotation.SearchRouting;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 带有分片路由键的分区订单测试实体
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@SearchIndex("routed_order_idx")
public class RoutedOrderDoc {

    @SearchId
    private String orderId;

    @SearchRouting
    private String tenantId;

    private BigDecimal amount;

    private String description;
}
