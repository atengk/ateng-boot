package io.github.atengk.boot.sample.job.snail.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.job.snail.dto.OrderPayRetryRequestDTO;
import io.github.atengk.boot.sample.job.snail.service.RetryDemoService;
import io.github.atengk.boot.sample.job.snail.vo.RetryExecutionVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * SnailJob 分布式协同重试演练领域控制器
 *
 * <p>提供声明式注解重试、编程式幂等重试、异步失败手动上报与重试度量指标巡检的 REST 端点。</p>
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/job-snail/retry")
@RequiredArgsConstructor
public class SnailJobRetryController {

    private final RetryDemoService retryDemoService;

    /**
     * 触发声明式 @AtengSnailRetry 订单支付重试方法
     */
    @PostMapping("/pay")
    public Result<RetryExecutionVO> triggerDeclarativePayRetry(@Valid @RequestBody OrderPayRetryRequestDTO request) {
        String outcome;
        String status = "SUCCESS";
        try {
            outcome = retryDemoService.payOrder(request.getOrderId(), request.getAmount());
        } catch (Exception e) {
            log.warn("[SnailJobRetryController] 支付触发业务异常 (已进入 SnailJob 重试管道): {}", e.getMessage());
            outcome = "Exception captured for retry: " + e.getMessage();
            status = "RETRY_TRIGGERED";
        }

        RetryExecutionVO vo = RetryExecutionVO.builder()
                .orderId(request.getOrderId())
                .scene("orderPayRetry")
                .idempotentId("orderPayRetry:" + request.getOrderId())
                .attemptCount(retryDemoService.getAttemptCount())
                .mode("DECLARATIVE")
                .status(status)
                .message(outcome)
                .build();
        return Result.success(vo);
    }

    /**
     * 触发 SnailRetryTemplate 编程式闭包幂等重试
     */
    @PostMapping("/pay/programmatic")
    public Result<RetryExecutionVO> triggerProgrammaticRetry(@RequestParam(defaultValue = "ORDER-PROG-1001") String orderId) {
        String outcome = retryDemoService.executeWithProgrammaticRetry(orderId);
        RetryExecutionVO vo = RetryExecutionVO.builder()
                .orderId(orderId)
                .scene("orderPayRetry")
                .idempotentId(orderId)
                .attemptCount(retryDemoService.getAttemptCount())
                .mode("PROGRAMMATIC")
                .status("SUCCESS")
                .message(outcome)
                .build();
        return Result.success(vo);
    }

    /**
     * 触发 SnailRetryTemplate 对称手动上报门面
     */
    @PostMapping("/pay/manual-report")
    public Result<RetryExecutionVO> triggerManualReport(@RequestParam(defaultValue = "ORDER-MANUAL-2001") String orderId) {
        boolean reported = retryDemoService.reportFailedOrder(orderId);
        RetryExecutionVO vo = RetryExecutionVO.builder()
                .orderId(orderId)
                .scene("orderPayRetry")
                .idempotentId(orderId)
                .attemptCount(retryDemoService.getAttemptCount())
                .mode("MANUAL_REPORT")
                .status(reported ? "REPORTED" : "REPORT_FAILED")
                .message("Manual retry report result: " + reported)
                .build();
        return Result.success(vo);
    }

    /**
     * 查询当前重试尝试计数与统计
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> getRetryStats() {
        return Result.success(Map.of(
                "scene", "orderPayRetry",
                "attemptCount", retryDemoService.getAttemptCount()
        ));
    }
}
