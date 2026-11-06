package io.github.atengk.boot.sample.liteflow.component.routing;

import io.github.atengk.boot.liteflow.component.BaseNodeComponent;
import io.github.atengk.boot.sample.liteflow.context.BatchSettleContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 批量结算汇总生成组件
 * 若链路被 endFlow() 提前终止，本节点将不会被触发
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Slf4j
@Component("settleSummaryCmp")
public class SettleSummaryCmp extends BaseNodeComponent<BatchSettleContext> {

    @Override
    public void process(BatchSettleContext context) {
        String summary = String.format("批次 [%s] 全量处理完成，成功结算 [%d] 笔，累计金额 [%s] 元，降级 [%d] 笔",
                context.getBatchNo(),
                context.getProcessedCount(),
                context.getSettledAmount().toPlainString(),
                context.getDegradedItems().size());
        context.setSummary(summary);
        log.info("【批量结算】生成最终批次摘要：{}", summary);
    }
}
