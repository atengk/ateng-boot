package io.github.atengk.boot.sample.liteflow.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.liteflow.core.LiteflowTemplate;
import io.github.atengk.boot.sample.liteflow.context.BatchSettleContext;
import io.github.atengk.boot.sample.liteflow.model.BatchSettleRequest;
import io.github.atengk.boot.sample.liteflow.model.BatchSettleResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;

/**
 * 批量路由与高级编排原语接口控制器
 * 演示：ITERATOR 强类型迭代、BaseIfComponent onError 就地优雅降级与 endFlow 提前正常熔断
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Slf4j
@RestController
@RequestMapping("/sample/liteflow/routing")
@RequiredArgsConstructor
public class LiteflowBatchRoutingController {

    private final LiteflowTemplate liteflowTemplate;

    /**
     * 批量订单结算路由流转
     *
     * @param request 批量结算请求参数
     * @return 批量结算结果与统计指标
     */
    @PostMapping("/batch-settle")
    public Result<BatchSettleResponse> batchSettle(@Valid @RequestBody BatchSettleRequest request) {
        log.info("【批量路由结算】接收结算批次：merchantId={}, batchNo={}, itemSize={}",
                request.getMerchantId(), request.getBatchNo(), request.getItems().size());

        BatchSettleContext context = new BatchSettleContext();
        // 依托强类型入参透传与预置业务上下文
        BatchSettleContext finalCtx = liteflowTemplate.executeWithContext(
                "batch-settle-flow", request, context);

        BatchSettleResponse response = BatchSettleResponse.builder()
                .batchNo(finalCtx.getBatchNo())
                .totalItems(request.getItems().size())
                .processedCount(finalCtx.getProcessedCount())
                .settledAmount(finalCtx.getSettledAmount())
                .settledItemIds(new ArrayList<>(finalCtx.getSettledItemIds()))
                .degradedItems(new ArrayList<>(finalCtx.getDegradedItems()))
                .taxAppliedItems(new ArrayList<>(finalCtx.getTaxAppliedItems()))
                .terminatedEarly(finalCtx.isTerminatedEarly())
                .summary(finalCtx.getSummary())
                .build();

        return Result.success(response);
    }
}
