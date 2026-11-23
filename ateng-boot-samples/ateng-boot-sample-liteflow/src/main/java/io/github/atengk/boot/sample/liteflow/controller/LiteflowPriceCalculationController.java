package io.github.atengk.boot.sample.liteflow.controller;

import io.github.atengk.boot.core.exception.BusinessException;
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

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 电商复合营销算价业务接口控制器
 * 演示：串行参数防卫 -> 并行查券与用户等级 -> 营销优惠汇总 -> 运费与终审算价，以及受管异步超时编排
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

        PriceCalculationContext context = buildContext(request);
        LiteflowTraceResult<PriceCalculationContext> traceResult = liteflowTemplate.executeWithTrace(
                "price-calc-flow", request, context);

        return Result.success(buildResponse(traceResult));
    }

    /**
     * 受管异步流式算价编排（带 Duration 硬超时与四维上下文透明透传）
     *
     * @param request 算价请求参数
     * @return 算价明细与时序轨迹
     */
    @PostMapping("/calculate-async")
    public Result<PriceCalculationResponse> calculatePriceAsync(@Valid @RequestBody PriceCalculationRequest request) {
        log.info("【电商算价-异步】接收异步订单算价请求：orderId={}, userId={}, amount={}",
                request.getOrderId(), request.getUserId(), request.getOriginalAmount());

        PriceCalculationContext context = buildContext(request);
        CompletableFuture<LiteflowTraceResult<PriceCalculationContext>> future = liteflowTemplate.executeAsyncWithTrace(
                "price-calc-flow",
                request,
                context,
                Duration.ofSeconds(3)
        );
        try {
            LiteflowTraceResult<PriceCalculationContext> traceResult = future.get(5, TimeUnit.SECONDS);
            return Result.success(buildResponse(traceResult));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("异步算价任务被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new BusinessException("异步算价任务执行异常", cause);
        } catch (TimeoutException e) {
            throw new BusinessException("异步算价任务等待超时", e);
        }
    }

    private PriceCalculationContext buildContext(PriceCalculationRequest request) {
        PriceCalculationContext context = new PriceCalculationContext();
        context.setOrderId(request.getOrderId());
        context.setUserId(request.getUserId());
        context.setOriginalAmount(request.getOriginalAmount());
        context.setCouponId(request.getCouponId());
        return context;
    }

    private PriceCalculationResponse buildResponse(LiteflowTraceResult<PriceCalculationContext> traceResult) {
        PriceCalculationContext finalCtx = traceResult.getContext();
        String traceSummary = traceResult.getTrace() != null
                ? traceResult.getTrace().formatSummary()
                : "N/A";

        return PriceCalculationResponse.builder()
                .orderId(finalCtx.getOrderId())
                .originalAmount(finalCtx.getOriginalAmount())
                .couponDiscount(finalCtx.getCouponDiscount())
                .levelDiscount(finalCtx.getLevelDiscount())
                .postage(finalCtx.getPostage())
                .finalAmount(finalCtx.getFinalAmount())
                .traceSummary(traceSummary)
                .build();
    }
}
