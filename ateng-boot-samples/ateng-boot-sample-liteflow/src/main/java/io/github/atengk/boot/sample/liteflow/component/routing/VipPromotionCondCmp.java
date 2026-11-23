package io.github.atengk.boot.sample.liteflow.component.routing;

import io.github.atengk.boot.liteflow.component.BaseIfComponent;
import io.github.atengk.boot.sample.liteflow.context.PromotionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * VIP 会员专属促销路由条件判断组件
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Slf4j
@Component("vipPromotionCondCmp")
public class VipPromotionCondCmp extends BaseIfComponent<PromotionContext> {

    @Override
    public boolean processIf(PromotionContext context) {
        boolean isVip = context != null && "VIP".equalsIgnoreCase(context.getUserLevel());
        log.info("【促销路由判定-VIP】用户 [{}], 会员等级 [{}], 判定结果: {}",
                context != null ? context.getUserId() : null,
                context != null ? context.getUserLevel() : null,
                isVip);
        return isVip;
    }
}
