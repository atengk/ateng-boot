package io.github.atengk.boot.sample.liteflow.component.routing;

import io.github.atengk.boot.liteflow.component.BaseNodeComponent;
import io.github.atengk.boot.sample.liteflow.context.BatchSettleContext;
import io.github.atengk.boot.sample.liteflow.model.SettleItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 纳税计算执行节点组件
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Slf4j
@Component("settleApplyTaxCmp")
public class SettleApplyTaxCmp extends BaseNodeComponent<BatchSettleContext> {

    @Override
    public void process(BatchSettleContext context) {
        SettleItem item = this.getCurrLoopObject(SettleItem.class);
        if (item != null) {
            context.getTaxAppliedItems().add(item.getItemId());
            log.info("【批量结算】明细 [{}] 完成纳税合规计算", item.getItemId());
        }
    }
}
