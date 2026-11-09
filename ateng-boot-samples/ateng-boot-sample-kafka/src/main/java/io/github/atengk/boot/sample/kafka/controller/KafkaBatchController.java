package io.github.atengk.boot.sample.kafka.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.kafka.dto.BatchLogResponse;
import io.github.atengk.boot.sample.kafka.dto.BatchOrderProduceRequest;
import io.github.atengk.boot.sample.kafka.dto.OrderProduceRequest;
import io.github.atengk.boot.sample.kafka.model.OrderMessage;
import io.github.atengk.boot.sample.kafka.service.BatchLogScenarioService;
import io.github.atengk.boot.sample.kafka.service.TradeKafkaScenarioService;
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
 * 微批聚合与批量拉取多租户安全隔离控制器
 *
 * <p>统一暴露微批订单聚合投递、单条报文超限（1MB）前置 Fail-Fast 拦截机制，
 * 以及由 {@link io.github.atengk.boot.kafka.executor.KafkaRecordExecutor} 安全还原多租户上下文的消费明细探查端点。</p>
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Slf4j
@RestController
@RequestMapping("/sample/kafka/batch")
@RequiredArgsConstructor
public class KafkaBatchController {

    private final TradeKafkaScenarioService tradeScenarioService;
    private final BatchLogScenarioService batchLogService;

    /**
     * 微批聚合发送订单列表
     *
     * @param request 批量订单投递请求
     * @return 已聚合投递的订单编号列表
     */
    @PostMapping("/orders")
    public Result<List<String>> produceBatchOrders(@Valid @RequestBody BatchOrderProduceRequest request) {
        log.info("[HTTP-BATCH-PRODUCE] 收到微批聚合订单投递请求: batchSize={}", request.getOrders().size());
        List<OrderMessage> orderMessages = request.getOrders().stream()
                .map(OrderProduceRequest::toOrderMessage)
                .toList();
        tradeScenarioService.sendBatchOrders(orderMessages);
        List<String> orderIds = orderMessages.stream().map(OrderMessage::getOrderId).toList();
        return Result.success(orderIds);
    }

    /**
     * 投递超限大报文（测试 1MB Fail-Fast 拦截机制）
     *
     * @param sizeBytes 拟发送载荷体积（字节，默认 1048640，即 1MB + 64B 超限报文）
     * @return 投递结果提示
     */
    @PostMapping("/large-payload")
    public Result<String> produceLargePayload(
            @RequestParam(value = "sizeBytes", required = false, defaultValue = "1048640") Integer sizeBytes) {
        int size = (sizeBytes != null && sizeBytes > 0) ? sizeBytes : (1024 * 1024 + 64);
        log.info("[HTTP-LARGE-PAYLOAD] 尝试发送载荷报文: sizeBytes={}", size);
        byte[] payload = new byte[size];
        tradeScenarioService.sendLargePayload(payload);
        return Result.success("发送成功");
    }

    /**
     * 探查批量多租户隔离执行器（KafkaRecordExecutor）安全处理的日志明细
     *
     * @return 批量消费记录响应列表
     */
    @GetMapping("/received-logs")
    public Result<List<BatchLogResponse>> getReceivedLogs() {
        List<BatchLogResponse> logs = batchLogService.getConsumerProcessedItems().stream()
                .map(item -> BatchLogResponse.builder()
                        .key(item.key())
                        .offset(item.offset())
                        .order(item.order())
                        .tenantId(item.tenantId())
                        .build())
                .toList();
        return Result.success(logs);
    }

    /**
     * 清理批量消费记录与内部状态
     *
     * @return 操作成功结果
     */
    @PostMapping("/clear")
    public Result<Void> clear() {
        batchLogService.clear();
        return Result.success();
    }
}
