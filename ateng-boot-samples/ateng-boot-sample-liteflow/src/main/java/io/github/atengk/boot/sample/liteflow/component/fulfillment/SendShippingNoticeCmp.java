package io.github.atengk.boot.sample.liteflow.component.fulfillment;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.liteflow.component.BaseNodeComponent;
import io.github.atengk.boot.sample.liteflow.context.OrderFulfillmentContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 用户触达出库发货通知投递组件
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@Component("sendShippingNoticeCmp")
public class SendShippingNoticeCmp extends BaseNodeComponent<OrderFulfillmentContext> {

    @Override
    public void process(OrderFulfillmentContext context) {
        if (context.isSimulateFailure()) {
            log.error("【履约流程-故障注入】模拟下游通知中心网络超时阻断，触发 Saga 逆向回滚补偿！orderId={}", context.getOrderId());
            throw new BusinessException("下游通知中心网络超时，触发逆序回滚补偿机制");
        }

        log.info("【履约流程-正向执行】出库发货短信与站内信通知投递成功：orderId={}", context.getOrderId());
        context.setShippingNoticeSent(true);
        context.setStatus("FULFILLED");
    }
}
