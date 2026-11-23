package io.github.atengk.boot.sample.kafka.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.kafka.dto.RetryLogResponse;
import io.github.atengk.boot.sample.kafka.dto.RetryTriggerRequest;
import io.github.atengk.boot.sample.kafka.service.NonBlockingRetryScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 阶梯延迟非阻塞重试控制器
 *
 * <p>统一暴露慢业务阶梯延迟非阻塞重试（{@code @RetryableTopic} 阶梯主题流转）触发与执行审计日志探查端点。</p>
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Slf4j
@RestController
@RequestMapping("/sample/kafka/retry")
@RequiredArgsConstructor
public class KafkaRetryController {

    private final NonBlockingRetryScenarioService retryService;

    /**
     * 投递消息触发阶梯延迟重试
     *
     * @param request 结构化重试触发请求（可选）
     * @param orderId 订单业务ID（当 request 为空时生效，默认 ORD-RETRY-001）
     * @param fail    是否模拟慢故障触发阶梯重试（默认 false 正常成功）
     * @return 触发结果
     */
    @PostMapping("/trigger")
    public Result<String> triggerRetry(
            @Valid @RequestBody(required = false) RetryTriggerRequest request,
            @RequestParam(value = "orderId", required = false, defaultValue = "ORD-RETRY-001") String orderId,
            @RequestParam(value = "fail", required = false, defaultValue = "false") boolean fail) {
        String targetOrderId = (request != null && request.getOrderId() != null && !request.getOrderId().isBlank())
                ? request.getOrderId()
                : orderId;
        boolean targetFail = (request != null && request.getFail() != null) ? request.getFail() : fail;
        String payload = targetFail ? "PAYLOAD_FAIL" : "PAYLOAD_SUCCESS";
        log.info("[HTTP-RETRY-TRIGGER] 投递重试消息: orderId={}, payload={}", targetOrderId, payload);
        retryService.sendRetryableMessage(targetOrderId, payload);
        return Result.success("重试消息发送成功: orderId=" + targetOrderId + ", payload=" + payload);
    }

    /**
     * 探查阶梯重试流转执行日志
     *
     * @return 规范的重试日志响应列表
     */
    @GetMapping("/logs")
    public Result<List<RetryLogResponse>> getRetryLogs() {
        List<RetryLogResponse> responses = retryService.getRetryLogs().stream()
                .map(log -> RetryLogResponse.builder()
                        .topic(log.topic())
                        .key(log.key())
                        .payload(log.payload())
                        .attempt(log.attempt())
                        .traceId(log.traceId())
                        .tenantId(log.tenantId())
                        .userId(log.userId())
                        .build())
                .toList();
        return Result.success(responses);
    }

    /**
     * 清理重试计数与日志状态
     *
     * @return 操作成功结果
     */
    @PostMapping("/clear")
    public Result<Void> clear() {
        retryService.clear();
        return Result.success();
    }
}
