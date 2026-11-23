package io.github.atengk.boot.sample.liteflow.component.routing;

import io.github.atengk.boot.liteflow.component.BaseNodeComponent;
import io.github.atengk.boot.sample.liteflow.context.PromotionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * VIP 会员专属 9 折折扣计算执行组件
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Slf4j
@Component("vipDiscountCmp")
public class VipDiscountCmp extends BaseNodeComponent<PromotionContext> {

    @Override
    public void process(PromotionContext context) {
        if (context == null || context.getCartAmount() == null) {
            return;
        }
        // VIP 享受原价 10% 优惠立减（即 9 折）
        BigDecimal discount = context.getCartAmount()
                .multiply(new BigDecimal("0.10"))
                .setScale(2, RoundingMode.HALF_UP);
        context.applyDiscount("VIP_9折特惠", discount, "VIP 会员尊享全场商品原价 9 折优惠");
        log.info("【促销计算-VIP】用户 [{}] 成功应用 VIP 9 折立减 ¥{}", context.getUserId(), discount);
    }
}
