package io.github.atengk.boot.sample.redis.model.lock;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 订单结算视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderSettleVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String orderId;
    private Long productId;
    private Integer remainingStock;
    private String status;
}
