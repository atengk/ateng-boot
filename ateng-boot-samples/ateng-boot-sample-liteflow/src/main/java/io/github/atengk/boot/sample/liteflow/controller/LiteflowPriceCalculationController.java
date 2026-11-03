package io.github.atengk.boot.sample.liteflow.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.liteflow.core.LiteflowTemplate;
import io.github.atengk.boot.liteflow.trace.LiteflowTraceResult;
import io.github.atengk.boot.sample.liteflow.context.PriceCalculationContext;
import io.github.atengk.boot.sample.liteflow.model.PriceCalculationRequest;
import io.github.atengk.boot.sample.liteflow.model.PriceCalculationResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 电商复合营销算价业务接口控制器
 * 演示：串行参数防卫 -> 并行查券与用户等级 -> 营销优惠汇总 -> 运费与终审算价
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@RestController
@RequestMapping("/sample/liteflow/price")
@RequiredArgsConstructor
public class LiteflowPriceCalculationController {

    private final LiteflowTemplate liteflowTemplate;

    /**
     * 电商订单复合营销算价
     *
     * @param request 算价请求参数
     * @return 算价明细与时序轨迹
     */
    @PostMapping("/calculate")
    public Result<PriceCalculationResponse> calculatePrice(@Valid @RequestBody PriceCalculationRequest request) {
        log.info("【电商算价】接收订单算价请求：orderId={}, userId={}, amount={}",
                request.getOrderId(), request.getUserId(), request.getOriginalAmount());

        PriceCalculationContext context = new PriceCalculationContext();
        context.setOrderId(request.getOrderId());
        context.setUserId(request.getUserId());
        context.setOriginalAmount(request.getOriginalAmount());
        context.setCouponId(request.getCouponId());

        LiteflowTraceResult<PriceCalculationContext> traceResult = liteflowTemplate.executeWithTrace(
                "price-calc-flow", request, context);

        PriceCalculationContext finalCtx = traceResult.getContext();
        String traceSummary = traceResult.getTrace() != null
                ? traceResult.getTrace().formatSummary()
                : "N/A";

        PriceCalculationResponse response = PriceCalculationResponse.builder()
                .orderId(finalCtx.getOrderId())
                .originalAmount(finalCtx.getOriginalAmount())
                .couponDiscount(finalCtx.getCouponDiscount())
                .levelDiscount(finalCtx.getLevelDiscount())
                .postage(finalCtx.getPostage())
                .finalAmount(finalCtx.getFinalAmount())
                .traceSummary(traceSummary)
                .build();

        return Result.success(response);
    }
}
