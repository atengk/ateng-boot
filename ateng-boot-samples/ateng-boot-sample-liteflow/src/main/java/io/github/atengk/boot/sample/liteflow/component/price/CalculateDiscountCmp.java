package io.github.atengk.boot.sample.liteflow.component.price;

import io.github.atengk.boot.liteflow.component.BaseNodeComponent;
import io.github.atengk.boot.sample.liteflow.context.PriceCalculationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 营销综合优惠汇总与折后试算组件
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@Component("calculateDiscountCmp")
public class CalculateDiscountCmp extends BaseNodeComponent<PriceCalculationContext> {

    @Override
    public void process(PriceCalculationContext context) {
        BigDecimal original = context.getOriginalAmount();
        BigDecimal couponDiscount = context.getCouponDiscount() == null ? BigDecimal.ZERO : context.getCouponDiscount();
        BigDecimal levelDiscount = context.getLevelDiscount() == null ? BigDecimal.ZERO : context.getLevelDiscount();

        BigDecimal totalDiscount = couponDiscount.add(levelDiscount);
        // 保障折后商品底价不低于 0.01 元
        BigDecimal floorAmount = new BigDecimal("0.01");
        BigDecimal maxAllowedDiscount = original.subtract(floorAmount);

        if (maxAllowedDiscount.compareTo(BigDecimal.ZERO) > 0 && totalDiscount.compareTo(maxAllowedDiscount) > 0) {
            log.info("【算价流程】总折扣 {} 超过允许最大优惠 {}，执行优惠封顶保底", totalDiscount, maxAllowedDiscount);
            // 优先缩减等级折扣
            BigDecimal allowedLevelDiscount = maxAllowedDiscount.subtract(couponDiscount);
            if (allowedLevelDiscount.compareTo(BigDecimal.ZERO) < 0) {
                context.setCouponDiscount(maxAllowedDiscount);
                context.setLevelDiscount(BigDecimal.ZERO);
            } else {
                context.setLevelDiscount(allowedLevelDiscount);
            }
        }
        log.info("【算价流程】优惠汇总完成，原价={}, 券抵扣={}, 会员抵扣={}",
                original, context.getCouponDiscount(), context.getLevelDiscount());
    }
}
