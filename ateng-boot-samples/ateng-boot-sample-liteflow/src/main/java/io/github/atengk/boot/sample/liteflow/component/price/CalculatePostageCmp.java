package io.github.atengk.boot.sample.liteflow.component.price;

import io.github.atengk.boot.liteflow.component.BaseNodeComponent;
import io.github.atengk.boot.sample.liteflow.context.PriceCalculationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 运费规则核算与最终应付金额终审组件
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@Component("calculatePostageCmp")
public class CalculatePostageCmp extends BaseNodeComponent<PriceCalculationContext> {

    private static final BigDecimal FREE_SHIPPING_THRESHOLD = new BigDecimal("99.00");
    private static final BigDecimal DEFAULT_POSTAGE = new BigDecimal("10.00");

    @Override
    public void process(PriceCalculationContext context) {
        BigDecimal original = context.getOriginalAmount();

        // 满 99 包邮策略
        BigDecimal postage = original.compareTo(FREE_SHIPPING_THRESHOLD) >= 0 ? BigDecimal.ZERO : DEFAULT_POSTAGE;
        context.setPostage(postage);

        // 最终金额 = (原价 - 券抵扣 - 等级抵扣) + 运费
        BigDecimal discountedPrice = original.subtract(context.getCouponDiscount()).subtract(context.getLevelDiscount());
        if (discountedPrice.compareTo(BigDecimal.ZERO) < 0) {
            discountedPrice = BigDecimal.ZERO;
        }
        BigDecimal finalAmount = discountedPrice.add(postage);
        context.setFinalAmount(finalAmount);

        log.info("【算价流程】运费核算完毕，运费={}, 最终应付金额={}", postage, finalAmount);
    }
}
