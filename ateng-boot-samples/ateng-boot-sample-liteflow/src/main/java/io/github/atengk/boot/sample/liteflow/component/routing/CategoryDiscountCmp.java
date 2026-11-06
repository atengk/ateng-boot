package io.github.atengk.boot.sample.liteflow.component.routing;

import io.github.atengk.boot.liteflow.component.BaseNodeComponent;
import io.github.atengk.boot.sample.liteflow.context.PromotionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 数码专属 20 元立减计算执行组件
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Slf4j
@Component("categoryDiscountCmp")
public class CategoryDiscountCmp extends BaseNodeComponent<PromotionContext> {

    private static final BigDecimal CATEGORY_DISCOUNT = new BigDecimal("20.00");

    @Override
    public void process(PromotionContext context) {
        if (context == null) {
            return;
        }
        context.applyDiscount("数码专属品类券", CATEGORY_DISCOUNT, "数码专属品类商品立减 20 元优惠");
        log.info("【促销计算-品类】用户 [{}] 成功应用数码品类专属立减 ¥{}", context.getUserId(), CATEGORY_DISCOUNT);
    }
}
