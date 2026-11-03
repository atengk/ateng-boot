package io.github.atengk.boot.sample.liteflow.component.fulfillment;

import io.github.atengk.boot.liteflow.component.BaseNodeComponent;
import io.github.atengk.boot.sample.liteflow.context.OrderFulfillmentContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 仓储库存锁定与扣减履约组件（支持 Saga 逆序补偿）
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@Component("deductInventoryCmp")
public class DeductInventoryCmp extends BaseNodeComponent<OrderFulfillmentContext> {

    @Override
    public void process(OrderFulfillmentContext context) {
        log.info("【履约流程-正向执行】锁定并扣减商品库存：skuId={}, quantity={}",
                context.getSkuId(), context.getQuantity());
        context.setInventoryDeducted(true);
    }

    @Override
    public void rollback(OrderFulfillmentContext context) {
        log.warn("【履约流程-逆向补偿】触发库存解冻回滚补偿：skuId={}, quantity={}",
                context.getSkuId(), context.getQuantity());
        context.setInventoryDeducted(false);
        context.addCompensationLog("【库存服务】解冻已锁定的商品库存: skuId=" + context.getSkuId() + ", quantity=" + context.getQuantity());
    }
}
