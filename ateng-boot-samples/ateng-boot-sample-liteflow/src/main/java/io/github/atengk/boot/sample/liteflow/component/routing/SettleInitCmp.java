package io.github.atengk.boot.sample.liteflow.component.routing;

import io.github.atengk.boot.liteflow.component.BaseNodeComponent;
import io.github.atengk.boot.sample.liteflow.context.BatchSettleContext;
import io.github.atengk.boot.sample.liteflow.model.BatchSettleRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 批量结算链路初始化组件
 * 提取强类型 BatchSettleRequest 请求入参并注入批次上下文
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Slf4j
@Component("settleInitCmp")
public class SettleInitCmp extends BaseNodeComponent<BatchSettleContext> {

    @Override
    public void process(BatchSettleContext context) {
        BatchSettleRequest request = this.getRequestData(BatchSettleRequest.class);
        if (request != null) {
            context.setMerchantId(request.getMerchantId());
            context.setBatchNo(request.getBatchNo());
            context.setTerminateOnSuspicious(request.isTerminateOnSuspicious());
            if (request.getItems() != null) {
                context.getItems().addAll(request.getItems());
            }
        }
        log.info("【批量结算】初始化批次完成：batchNo={}, itemSize={}", context.getBatchNo(), context.getItems().size());
    }
}
