package io.github.atengk.boot.sample.liteflow.component.routing;

import io.github.atengk.boot.liteflow.component.BaseIfComponent;
import io.github.atengk.boot.sample.liteflow.context.PromotionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 数码专属品类券条件判断组件
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Slf4j
@Component("categoryPromotionCondCmp")
public class CategoryPromotionCondCmp extends BaseIfComponent<PromotionContext> {

    @Override
    public boolean processIf(PromotionContext context) {
        boolean matched = context != null
                && "DIGITAL".equalsIgnoreCase(context.getCategory());
        log.info("【促销路由判定-品类】商品品类 [{}], 是否属于数码品类: {}",
                context != null ? context.getCategory() : null, matched);
        return matched;
    }
}
