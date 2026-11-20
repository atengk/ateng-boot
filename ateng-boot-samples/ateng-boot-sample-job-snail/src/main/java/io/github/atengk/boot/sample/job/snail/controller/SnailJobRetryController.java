package io.github.atengk.boot.sample.job.snail.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.job.snail.dto.OrderPayRetryRequestDTO;
import io.github.atengk.boot.sample.job.snail.service.RetryDemoService;
import io.github.atengk.boot.sample.job.snail.vo.RetryExecutionVO;
import io.github.atengk.boot.sample.job.snail.vo.RetryStatsVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

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
     *
     * @param request 订单支付重试请求 DTO
     * @return 重试执行结果视图
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
     * 触发声明式 @AtengSnailRetry(value = "orderRefundRetry") value 别名订单退款重试
     *
     * @param refundNo 退款单号
     * @param amount   退款金额
     * @return 重试执行结果视图
     */
    @PostMapping("/refund")
    public Result<RetryExecutionVO> triggerRefundAliasRetry(
            @RequestParam(defaultValue = "REFUND-ALIAS-1001") @NotBlank(message = "退款单号 refundNo 不能为空") String refundNo,
            @RequestParam(defaultValue = "66.00") java.math.BigDecimal amount) {
        String outcome;
        String status = "SUCCESS";
        try {
            outcome = retryDemoService.refundOrder(refundNo, amount);
        } catch (Exception e) {
            log.warn("[SnailJobRetryController] 退款触发业务异常 (已进入 SnailJob 重试管道): {}", e.getMessage());
            outcome = "Exception captured for refund retry: " + e.getMessage();
            status = "RETRY_TRIGGERED";
        }
        RetryExecutionVO vo = RetryExecutionVO.builder()
                .orderId(refundNo)
                .scene("orderRefundRetry")
                .idempotentId("orderRefundRetry_" + refundNo)
                .attemptCount(retryDemoService.getAttemptCount())
                .mode("DECLARATIVE_VALUE_ALIAS")
                .status(status)
                .message(outcome)
                .build();
        return Result.success(vo);
    }

    /**
     * 触发 @AtengSnailRetry 与 @Retryable 混用向后兼容演示
     *
     * @param orderId 业务订单编号
     * @return 重试执行结果视图
     */
    @PostMapping("/legacy-compat")
    public Result<RetryExecutionVO> triggerLegacyDualCompat(
            @RequestParam(defaultValue = "ORD-COMPAT-1001") @NotBlank(message = "订单编号 orderId 不能为空") String orderId) {
        String outcome = retryDemoService.legacyDualCompatNotify(orderId);
        RetryExecutionVO vo = RetryExecutionVO.builder()
                .orderId(orderId)
                .scene("legacyDualCompatRetry")
                .idempotentId("legacyDualCompatRetry_" + orderId)
                .attemptCount(retryDemoService.getAttemptCount())
                .mode("DUAL_ANNOTATION_COMPAT")
                .status("SUCCESS")
                .message(outcome)
                .build();
        return Result.success(vo);
    }

    /**
     * 触发 SnailRetryTemplate 编程式闭包幂等重试
     *
     * @param orderId 业务订单编号
     * @return 重试执行结果视图
     */
    @PostMapping("/pay/programmatic")
    public Result<RetryExecutionVO> triggerProgrammaticRetry(
            @RequestParam(defaultValue = "ORDER-PROG-1001") @NotBlank(message = "订单编号 orderId 不能为空") String orderId) {
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
     *
     * @param orderId 业务订单编号
     * @return 重试上报结果视图
     */
    @PostMapping("/pay/manual-report")
    public Result<RetryExecutionVO> triggerManualReport(
            @RequestParam(defaultValue = "ORDER-MANUAL-2001") @NotBlank(message = "订单编号 orderId 不能为空") String orderId) {
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
     *
     * @return 强类型重试统计视图
     */
    @GetMapping("/stats")
    public Result<RetryStatsVO> getRetryStats() {
        return Result.success(RetryStatsVO.builder()
                .scene("orderPayRetry")
                .attemptCount(retryDemoService.getAttemptCount())
                .build());
    }
}
