package io.github.atengk.boot.sample.redis.model.lock;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 促销规则更新（写锁）请求传输对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromotionRuleUpdateRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "规则 ID 不能为空")
    private String ruleId;

    @NotBlank(message = "规则名称不能为空")
    private String ruleName;

    @NotNull(message = "折扣比例不能为空")
    @DecimalMin(value = "0.01", message = "折扣比例不能低于 0.01")
    @DecimalMax(value = "1.00", message = "折扣比例不能高于 1.00")
    private BigDecimal discountRate;
}
