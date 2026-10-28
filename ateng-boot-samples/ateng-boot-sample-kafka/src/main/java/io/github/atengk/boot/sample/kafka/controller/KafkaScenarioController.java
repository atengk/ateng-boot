package io.github.atengk.boot.sample.kafka.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.validation.ValidList;
import io.github.atengk.boot.sample.kafka.model.OrderMessage;
import io.github.atengk.boot.sample.kafka.model.UserEventMessage;
import io.github.atengk.boot.sample.kafka.producer.TradeKafkaProducer;
import io.github.atengk.boot.sample.kafka.service.KafkaOrderTransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.support.SendResult;
import org.springframework.web.bind.annotation.*;

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
    public Result<String> sendSync(@RequestBody @Valid OrderMessage order) {
        SendResult<Object, Object> result = producer.sendSyncOrder(order);
        return Result.success("发送成功: offset=" + result.getRecordMetadata().offset());
    }

    @PostMapping("/user/async")
    public Result<String> sendAsync(@RequestBody @Valid UserEventMessage event) {
        CompletableFuture<SendResult<Object, Object>> future = producer.sendAsyncUserEvent(event);
        future.thenAccept(res -> log.info("[ASYNC-SENT] 异步发信成功: offset={}", res.getRecordMetadata().offset()));
        return Result.success("异步发送已提交");
    }

    @PostMapping("/order/tx-success")
    public Result<String> txSuccess(@RequestBody @Valid OrderMessage order) {
        transactionService.createOrderWithSendAfterCommit(order);
        return Result.success("事务提交成功，消息已投递");
    }

    @PostMapping("/order/tx-rollback")
    public Result<String> txRollback(@RequestBody @Valid OrderMessage order) {
        transactionService.createOrderWithRollback(order);
        return Result.success("本地事务已执行");
    }

    @PostMapping("/order/batch")
    public Result<Integer> sendBatch(@RequestBody @Valid ValidList<OrderMessage> orders) {
        var results = producer.sendBatchOrders(orders);
        return Result.success(results.size());
    }

    @PostMapping("/poison")
    public Result<String> sendPoison(@RequestParam(defaultValue = "POISON_CORRUPTED_PAYLOAD") String data) {
        producer.sendPoisonPill(data);
        return Result.success("毒丸报文已发射");
    }
}
