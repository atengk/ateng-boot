package io.github.atengk.boot.sample.liteflow.component.routing;

import io.github.atengk.boot.liteflow.component.BaseIfComponent;
import io.github.atengk.boot.sample.liteflow.context.PromotionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 满额大促条件判断组件（满 200 元生效）
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Slf4j
@Component("thresholdPromotionCondCmp")
public class ThresholdPromotionCondCmp extends BaseIfComponent<PromotionContext> {

    private static final BigDecimal THRESHOLD = new BigDecimal("200.00");

    @Override
    public boolean processIf(PromotionContext context) {
        boolean matched = context != null
                && context.getCartAmount() != null
                && context.getCartAmount().compareTo(THRESHOLD) >= 0;
        log.info("【促销路由判定-满额】购物车金额 [¥{}], 阈值 [¥{}], 判定结果: {}",
                context != null ? context.getCartAmount() : null, THRESHOLD, matched);
        return matched;
    }
}
