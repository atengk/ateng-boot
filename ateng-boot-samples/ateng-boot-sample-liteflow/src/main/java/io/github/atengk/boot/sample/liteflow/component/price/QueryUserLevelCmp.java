package io.github.atengk.boot.sample.liteflow.component.price;

import io.github.atengk.boot.liteflow.component.BaseNodeComponent;
import io.github.atengk.boot.sample.liteflow.context.PriceCalculationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 用户会员等级与专属权益并行查询组件
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@Component("queryUserLevelCmp")
public class QueryUserLevelCmp extends BaseNodeComponent<PriceCalculationContext> {

    @Override
    public void process(PriceCalculationContext context) {
        String userId = context.getUserId();
        if (userId != null && userId.toLowerCase().contains("svip")) {
            context.setUserLevel("SVIP");
            context.setLevelDiscount(new BigDecimal("30.00"));
        } else if (userId != null && userId.toLowerCase().contains("vip")) {
            context.setUserLevel("VIP");
            context.setLevelDiscount(new BigDecimal("10.00"));
        } else {
            context.setUserLevel("NORMAL");
            context.setLevelDiscount(BigDecimal.ZERO);
        }
        log.info("【算价流程-并行查等级】用户等级评估完成，userId={}, level={}, discount={}",
                userId, context.getUserLevel(), context.getLevelDiscount());
    }
}
