package io.github.atengk.boot.sample.liteflow.component.price;

import io.github.atengk.boot.liteflow.component.BaseNodeComponent;
import io.github.atengk.boot.sample.liteflow.context.PriceCalculationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 优惠券并行查询与抵扣试算组件
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@Component("queryCouponCmp")
public class QueryCouponCmp extends BaseNodeComponent<PriceCalculationContext> {

    @Override
    public void process(PriceCalculationContext context) {
        String couponId = context.getCouponId();
        if (couponId == null || couponId.isBlank()) {
            log.info("【算价流程-并行查券】未选用优惠券，抵扣 0 元");
            context.setCouponDiscount(BigDecimal.ZERO);
            return;
        }

        BigDecimal discount;
        if ("COUPON_50".equalsIgnoreCase(couponId)) {
            discount = new BigDecimal("50.00");
        } else if ("COUPON_10".equalsIgnoreCase(couponId)) {
            discount = new BigDecimal("10.00");
        } else {
            discount = new BigDecimal("20.00");
        }
        log.info("【算价流程-并行查券】命中优惠券：couponId={}, 抵扣金额={}", couponId, discount);
        context.setCouponDiscount(discount);
    }
}
