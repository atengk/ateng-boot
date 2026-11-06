package io.github.atengk.boot.sample.liteflow.component.routing;

import io.github.atengk.boot.liteflow.component.BaseNodeComponent;
import io.github.atengk.boot.sample.liteflow.context.BatchSettleContext;
import io.github.atengk.boot.sample.liteflow.model.SettleItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 单条明细结算入账与风控拦截组件
 * 演示：getCurrLoopObject 强类型获取、getLoopIndex 轮次获取，以及 endFlow 提前正常熔断终止整条流水线
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Slf4j
@Component("settleItemProcessCmp")
public class SettleItemProcessCmp extends BaseNodeComponent<BatchSettleContext> {

    @Override
    public void process(BatchSettleContext context) {
        SettleItem item = this.getCurrLoopObject(SettleItem.class);
        Integer loopIndex = this.getLoopIndex();
        if (item == null) {
            return;
        }

        // 遭遇高风险可疑项且配置了提前熔断时，触发 endFlow() 提前正常终止
        if (item.isSuspicious() && context.isTerminateOnSuspicious()) {
            log.warn("【批量结算-风控熔断】第 [{}] 轮迭代发现高危可疑明细 [itemId={}]，触发 endFlow() 提前终止批次流转",
                    loopIndex, item.getItemId());
            context.setTerminatedEarly(true);
            this.endFlow();
            return;
        }

        // 正常结算累加
        context.setProcessedCount(context.getProcessedCount() + 1);
        context.setSettledAmount(context.getSettledAmount().add(item.getAmount()));
        context.getSettledItemIds().add(item.getItemId());
        log.info("【批量结算】第 [{}] 轮结算明细成功：itemId={}, amount={}, 累计结算={}",
                loopIndex, item.getItemId(), item.getAmount(), context.getSettledAmount());
    }
}
