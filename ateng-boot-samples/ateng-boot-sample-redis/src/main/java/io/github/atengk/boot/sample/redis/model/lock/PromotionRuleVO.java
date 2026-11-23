package io.github.atengk.boot.sample.redis.model.lock;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 促销规则视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromotionRuleVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String ruleId;
    private String ruleName;
    private BigDecimal discountRate;
    private Long updateTime;
}
