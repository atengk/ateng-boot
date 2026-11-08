package io.github.atengk.boot.sample.redis.model.ops;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 优惠券原子核销请求参数
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CouponDeductRequest {

    @NotBlank(message = "优惠券 ID 不能为空")
    private String couponId;

    @NotNull(message = "扣减数量不能为空")
    @Min(value = 1, message = "扣减数量必须大于等于 1")
    private Integer deductCount;
}
