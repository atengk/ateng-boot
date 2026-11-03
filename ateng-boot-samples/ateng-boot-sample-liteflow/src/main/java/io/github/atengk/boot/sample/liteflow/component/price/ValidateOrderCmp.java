package io.github.atengk.boot.sample.liteflow.component.price;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.liteflow.component.BaseNodeComponent;
import io.github.atengk.boot.sample.liteflow.context.PriceCalculationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 订单有效性与金额校验组件
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@Component("validateOrderCmp")
public class ValidateOrderCmp extends BaseNodeComponent<PriceCalculationContext> {

    @Override
    public void process(PriceCalculationContext context) {
        log.info("【算价流程】开始校验订单参数，orderId={}, amount={}", context.getOrderId(), context.getOriginalAmount());
        if (context.getOriginalAmount() == null || context.getOriginalAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("订单原始金额必须大于 0");
        }
    }
}
