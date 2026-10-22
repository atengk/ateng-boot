package io.github.atengk.boot.sample.kafka.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.kafka.model.OrderMessage;
import io.github.atengk.boot.sample.kafka.model.UserEventMessage;
import io.github.atengk.boot.sample.kafka.producer.TradeKafkaProducer;
import io.github.atengk.boot.sample.kafka.service.KafkaOrderTransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.support.SendResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Kafka 场景样例 HTTP 体验接口
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@RestController
@RequestMapping("/kafka")
@RequiredArgsConstructor
public class KafkaScenarioController {

    private final TradeKafkaProducer producer;
    private final KafkaOrderTransactionService transactionService;

    @PostMapping("/order/sync")
    public Result<String> sendSync(@RequestBody OrderMessage order) {
        SendResult<Object, Object> result = producer.sendSyncOrder(order);
        return Result.success("发送成功: offset=" + result.getRecordMetadata().offset());
    }

    @PostMapping("/user/async")
    public Result<String> sendAsync(@RequestBody UserEventMessage event) {
        CompletableFuture<SendResult<Object, Object>> future = producer.sendAsyncUserEvent(event);
        future.thenAccept(res -> log.info("[ASYNC-SENT] 异步发信成功: offset={}", res.getRecordMetadata().offset()));
        return Result.success("异步发送已提交");
    }

    @PostMapping("/order/tx-success")
    public Result<String> txSuccess(@RequestBody OrderMessage order) {
        transactionService.createOrderWithSendAfterCommit(order);
        return Result.success("事务提交成功，消息已投递");
    }

    @PostMapping("/order/tx-rollback")
    public Result<String> txRollback(@RequestBody OrderMessage order) {
        try {
            transactionService.createOrderWithRollback(order);
        } catch (Exception ex) {
            log.warn("[TX-ROLLBACK] 捕获预期回滚异常: {}", ex.getMessage());
            return Result.success("本地事务已回滚，确认未发信: " + ex.getMessage());
        }
        return Result.fail("未按预期回滚");
    }

    @PostMapping("/order/batch")
    public Result<Integer> sendBatch(@RequestBody List<OrderMessage> orders) {
        var results = producer.sendBatchOrders(orders);
        return Result.success(results.size());
    }

    @PostMapping("/poison")
    public Result<String> sendPoison(@RequestParam(defaultValue = "POISON_CORRUPTED_PAYLOAD") String data) {
        producer.sendPoisonPill(data);
        return Result.success("毒丸报文已发射");
    }
}
