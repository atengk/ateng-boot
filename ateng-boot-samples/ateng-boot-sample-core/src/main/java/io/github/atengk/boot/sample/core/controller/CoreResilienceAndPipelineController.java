package io.github.atengk.boot.sample.core.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.pipeline.PipelineResult;
import io.github.atengk.boot.sample.core.dto.ExchangeRateRequest;
import io.github.atengk.boot.sample.core.dto.OrderPipelineRequest;
import io.github.atengk.boot.sample.core.dto.RateLimitRequest;
import io.github.atengk.boot.sample.core.service.CorePipelineScenarioService;
import io.github.atengk.boot.sample.core.service.CoreResilienceScenarioService;
import io.github.atengk.boot.sample.core.vo.ExchangeRateVO;
import io.github.atengk.boot.sample.core.vo.OrderPipelineVO;
import io.github.atengk.boot.sample.core.vo.RateLimitVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Core 弹性容错、流量整形与业务管道编排控制器
 * 演示指数退避重试与 Fallback 降级、单机滑动窗口防刷、纯无锁 CAS 令牌桶限流、幂等防重拦截与多阶段业务管道编排短路机制
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Slf4j
@RestController
@RequestMapping("/sample/core/resilience")
@RequiredArgsConstructor
@Validated
public class CoreResilienceAndPipelineController {

    private final CoreResilienceScenarioService resilienceService;
    private final CorePipelineScenarioService pipelineService;

    /**
     * 1. 跨境汇率网关弹性查询与平滑重试降级
     *
     * @param request 汇率查询请求载荷
     * @return 跨境汇率响应视图对象
     */
    @PostMapping("/retry-exchange-rate")
    public Result<ExchangeRateVO> queryExchangeRateWithRetry(@RequestBody @Valid ExchangeRateRequest request) {
        log.info("接收跨境汇率弹性查询请求: pair={}/{}, simulateFailures={}",
                request.getFromCurrency(), request.getToCurrency(), request.getSimulateFailures());
        ExchangeRateVO vo = resilienceService.queryExchangeRateWithRetry(request);
        return Result.success(vo);
    }

    /**
     * 2. 接口防刷、流量整形与防重幂等校验
     *
     * @param request 流量整形请求参数
     * @return 流量整形与防重状态
     */
    @PostMapping("/rate-limit")
    public Result<RateLimitVO> checkRateLimitAndIdempotent(@RequestBody @Valid RateLimitRequest request) {
        log.info("接收防刷与幂等检测请求: clientId={}, reqToken={}, triggerCheck={}",
                request.getClientId(), request.getReqToken(), request.isTriggerIdempotentCheck());
        RateLimitVO vo = resilienceService.executeRateLimitCheck(request);
        return Result.success(vo);
    }

    /**
     * 3. 电商提单多阶段责任链管道编排
     *
     * @param request 提单责任链请求参数
     * @return 提单流水线执行结果模型
     */
    @PostMapping("/order-pipeline")
    public Result<OrderPipelineVO> processOrderCheckoutPipeline(@RequestBody @Valid OrderPipelineRequest request) {
        log.info("接收电商提单管道编排请求: orderNo={}, userId={}, sku={}, buyCount={}, stock={}",
                request.getOrderNo(), request.getUserId(), request.getSkuCode(), request.getBuyCount(), request.getAvailableStock());

        // 1. 构建流水线上下文与商品项列表
        CorePipelineScenarioService.OrderItem item = CorePipelineScenarioService.OrderItem.builder()
                .skuCode(request.getSkuCode())
                .quantity(request.getBuyCount())
                .availableStock(request.getAvailableStock())
                .build();

        CorePipelineScenarioService.OrderCheckoutContext context = CorePipelineScenarioService.OrderCheckoutContext.builder()
                .orderNo(request.getOrderNo())
                .userId(request.getUserId())
                .orderAmount(request.getOrderAmount())
                .items(List.of(item))
                .auditTrail(new ArrayList<>())
                .build();

        // 2. 执行多阶段管道责任链
        PipelineResult result = pipelineService.processCheckout(context);

        // 3. 封装出参视图对象
        OrderPipelineVO vo = OrderPipelineVO.builder()
                .orderNo(request.getOrderNo())
                .passed(result.isSuccess())
                .terminated(result.isTerminated())
                .terminatedStageName(result.getTerminatedStageName())
                .interruptReason(result.getInterruptReason())
                .discountAmount(context.getDiscountAmount())
                .auditTrail(context.getAuditTrail())
                .build();

        return Result.success(vo);
    }
}
