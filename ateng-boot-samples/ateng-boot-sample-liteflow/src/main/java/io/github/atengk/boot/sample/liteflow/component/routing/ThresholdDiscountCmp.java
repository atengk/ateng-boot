package io.github.atengk.boot.sample.liteflow.component.routing;

import io.github.atengk.boot.liteflow.component.BaseNodeComponent;
import io.github.atengk.boot.sample.liteflow.context.PromotionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 满 200 减 30 元满减计算执行组件
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Slf4j
@Component("thresholdDiscountCmp")
public class ThresholdDiscountCmp extends BaseNodeComponent<PromotionContext> {

    private static final BigDecimal DISCOUNT_AMOUNT = new BigDecimal("30.00");

    @Override
    public void process(PromotionContext context) {
        if (context == null) {
            return;
        }
        context.applyDiscount("满200减30大促", DISCOUNT_AMOUNT, "订单商品实付总额满 200 元立减 30 元");
        log.info("【促销计算-满额】用户 [{}] 成功应用满 200 减 30 元", context.getUserId());
    }
}
