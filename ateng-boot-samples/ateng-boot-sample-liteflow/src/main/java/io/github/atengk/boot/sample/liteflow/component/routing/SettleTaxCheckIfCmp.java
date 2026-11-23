package io.github.atengk.boot.sample.liteflow.component.routing;

import io.github.atengk.boot.liteflow.component.BaseIfComponent;
import io.github.atengk.boot.sample.liteflow.context.BatchSettleContext;
import io.github.atengk.boot.sample.liteflow.model.SettleItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 税务合规判定条件组件
 * 演示：调用外部税务服务异常时，通过重写 onError 实现控制流就地优雅降级
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Slf4j
@Component("settleTaxCheckIfCmp")
public class SettleTaxCheckIfCmp extends BaseIfComponent<BatchSettleContext> {

    @Override
    public boolean processIf(BatchSettleContext context) {
        SettleItem item = this.getCurrLoopObject(SettleItem.class);
        if (item != null && item.isSimulateTaxError()) {
            throw new IllegalStateException("模拟外部税务计算服务超时中断 [itemId=" + item.getItemId() + "]");
        }
        // 金额大于等于 100 元需进行税务核算
        return item != null && item.getAmount().doubleValue() >= 100.0;
    }

    @Override
    public boolean onError(Exception e, BatchSettleContext context) {
        SettleItem item = this.getCurrLoopObject(SettleItem.class);
        String itemId = item != null ? item.getItemId() : "UNKNOWN";
        log.warn("【批量结算-税务降级】明细 [{}] 税务服务调用异常，触发 onError 就地优雅降级: {}", itemId, e.getMessage());
        context.getDegradedItems().add(itemId);
        // 就地降级走假分支（免税/后续异步核税补单），不中断整个批次流转
        return false;
    }
}
