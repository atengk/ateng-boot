package io.github.atengk.boot.sample.liteflow.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.liteflow.core.LiteflowTemplate;
import io.github.atengk.boot.liteflow.exception.LiteflowExecutionException;
import io.github.atengk.boot.liteflow.trace.LiteflowExecutionTrace;
import io.github.atengk.boot.liteflow.trace.LiteflowTraceResult;
import io.github.atengk.boot.sample.liteflow.context.OrderFulfillmentContext;
import io.github.atengk.boot.sample.liteflow.model.OrderFulfillmentRequest;
import io.github.atengk.boot.sample.liteflow.model.OrderFulfillmentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;

/**
 * 订单履约流转与 Saga 逆序补偿接口控制器
 * 演示：库存锁定 -> 运单派发 -> 发货通知（模拟故障自动触发前序节点逆序 rollback 补偿）
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@RestController
@RequestMapping("/sample/liteflow/fulfillment")
@RequiredArgsConstructor
public class LiteflowOrderFulfillmentController {

    private final LiteflowTemplate liteflowTemplate;

    /**
     * 执行订单履约流程（支持故障注入与 Saga 逆向补偿体验）
     *
     * @param request 履约请求
     * @return 履约状态与补偿审计日志
     */
    @PostMapping("/fulfill")
    public Result<OrderFulfillmentResponse> fulfillOrder(@Valid @RequestBody OrderFulfillmentRequest request) {
        log.info("【订单履约】接收履约调度指令：orderId={}, skuId={}, quantity={}, simulateFailure={}",
                request.getOrderId(), request.getSkuId(), request.getQuantity(), request.getSimulateFailure());

        OrderFulfillmentContext context = new OrderFulfillmentContext();
        context.setOrderId(request.getOrderId());
        context.setSkuId(request.getSkuId());
        context.setQuantity(request.getQuantity());
        context.setSimulateFailure(Boolean.TRUE.equals(request.getSimulateFailure()));

        try {
            LiteflowTraceResult<OrderFulfillmentContext> traceResult = liteflowTemplate.executeWithTrace(
                    "order-fulfillment-flow", request, context);

            OrderFulfillmentContext finalCtx = traceResult.getContext();
            String traceSummary = traceResult.getTrace() != null
                    ? traceResult.getTrace().formatSummary()
                    : "N/A";

            OrderFulfillmentResponse response = OrderFulfillmentResponse.builder()
                    .orderId(finalCtx.getOrderId())
                    .success(true)
                    .fulfillmentStatus(finalCtx.getStatus())
                    .compensationLogs(new ArrayList<>(finalCtx.getCompensationLogs()))
                    .errorSummary(null)
                    .traceSummary(traceSummary)
                    .build();

            return Result.success(response);
        } catch (Exception ex) {
            log.warn("【订单履约】履约链路异常中断，Saga 逆向补偿已执行：orderId={}, cause={}",
                    request.getOrderId(), ex.getMessage());

            String traceSummary = "N/A";
            if (ex instanceof LiteflowExecutionException lee && lee.getTrace() != null) {
                LiteflowExecutionTrace trace = lee.getTrace();
                traceSummary = trace.formatSummary();
            }

            OrderFulfillmentResponse response = OrderFulfillmentResponse.builder()
                    .orderId(context.getOrderId())
                    .success(false)
                    .fulfillmentStatus("COMPENSATED")
                    .compensationLogs(new ArrayList<>(context.getCompensationLogs()))
                    .errorSummary(ex.getMessage())
                    .traceSummary(traceSummary)
                    .build();

            return Result.success(response);
        }
    }
}
