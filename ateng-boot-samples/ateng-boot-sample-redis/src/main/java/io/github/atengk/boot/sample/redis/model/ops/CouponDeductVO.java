package io.github.atengk.boot.sample.redis.model.ops;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 优惠券扣减响应视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CouponDeductVO {

    private String couponId;
    private Integer deductCount;
    private Long remainingStock;
    private Boolean success;
    private String message;
}
