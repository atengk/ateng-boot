package io.github.atengk.boot.sample.liteflow.component.fulfillment;

import io.github.atengk.boot.liteflow.component.BaseNodeComponent;
import io.github.atengk.boot.sample.liteflow.context.OrderFulfillmentContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 仓储物流中心派车发货运单生成组件（支持 Saga 逆序补偿）
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@Component("createWarehouseOrderCmp")
public class CreateWarehouseOrderCmp extends BaseNodeComponent<OrderFulfillmentContext> {

    @Override
    public void process(OrderFulfillmentContext context) {
        log.info("【履约流程-正向执行】仓储系统生成出库调度运单：orderId={}", context.getOrderId());
        context.setWarehouseOrderCreated(true);
    }

    @Override
    public void rollback(OrderFulfillmentContext context) {
        log.warn("【履约流程-逆向补偿】作废并取消出库调度运单：orderId={}", context.getOrderId());
        context.setWarehouseOrderCreated(false);
        context.addCompensationLog("【仓储物流】作废已生成的出库调度运单: orderId=" + context.getOrderId());
    }
}
