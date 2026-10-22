package io.github.atengk.boot.sample.rocketmq.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import io.github.atengk.boot.sample.rocketmq.producer.TradeRocketmqProducer;
import io.github.atengk.boot.sample.rocketmq.service.RocketmqOrderTransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * RocketMQ 场景沙箱 HTTP 体验接口
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@RestController
@RequestMapping("/rocketmq")
@RequiredArgsConstructor
public class RocketmqScenarioController {

    private final TradeRocketmqProducer producer;
    private final RocketmqOrderTransactionService transactionService;

    @PostMapping("/order/sync")
    public Result<String> sendSync(@RequestBody @Valid OrderMessage order) {
        SendReceipt receipt = producer.sendOrder(order);
        return Result.success("同步发送成功: messageId=" + receipt.getMessageId());
    }

    @PostMapping("/order/async")
    public Result<String> sendAsync(@RequestBody @Valid OrderMessage order) {
        CompletableFuture<SendReceipt> future = producer.sendOrderAsync(order);
        future.thenAccept(r -> log.info("[ASYNC-CALLBACK] 异步发送完成: messageId={}", r.getMessageId()));
        return Result.success("异步发送已提交");
    }

    @PostMapping("/order/delay")
    public Result<String> sendDelay(@RequestBody @Valid OrderMessage order, @RequestParam(defaultValue = "5") int seconds) {
        SendReceipt receipt = producer.sendOrderDelay(order, Duration.ofSeconds(seconds));
        return Result.success("延时消息发送成功: messageId=" + receipt.getMessageId() + ", delay=" + seconds + "s");
    }

    @PostMapping("/order/fifo")
    public Result<String> sendFifo(@RequestBody @Valid OrderMessage order, @RequestParam(defaultValue = "DEFAULT_GROUP") String group) {
        SendReceipt receipt = producer.sendOrderFifo(order, group);
        return Result.success("顺序消息发送成功: messageId=" + receipt.getMessageId() + ", group=" + group);
    }

    @PostMapping("/order/tx-2pc")
    public Result<String> sendTx2Pc(@RequestBody @Valid OrderMessage order, @RequestParam(defaultValue = "true") boolean success) {
        SendReceipt receipt = transactionService.createOrderWith2PcTransaction(order, success);
        return Result.success("2PC 事务半消息完成: messageId=" + receipt.getMessageId() + ", success=" + success);
    }

    @PostMapping("/order/tx-commit")
    public Result<String> sendAfterCommit(@RequestBody @Valid OrderMessage order) {
        transactionService.createOrderWithSendAfterCommit(order);
        return Result.success("本地事务提交成功，消息已挂载投递");
    }

    @PostMapping("/order/tx-rollback")
    public Result<String> sendAfterRollback(@RequestBody @Valid OrderMessage order) {
        try {
            transactionService.createOrderWithRollback(order);
        } catch (Exception ex) {
            log.warn("[TX-ROLLBACK] 本地事务已如预期回滚: {}", ex.getMessage());
        }
        return Result.success("本地事务已触发回滚，消息已被安全丢弃");
    }

    @PostMapping("/order/sql92")
    public Result<String> sendSql92(@RequestBody @Valid OrderMessage order, @RequestParam(defaultValue = "200") String amountStr) {
        SendReceipt receipt = producer.sendOrderWithSqlProperties(order, Map.of("amount", amountStr));
        return Result.success("SQL92 消息发送成功: messageId=" + receipt.getMessageId());
    }

    @PostMapping("/order/poison")
    public Result<String> sendPoison(@RequestParam(defaultValue = "POISON_FATAL_ORDER") String orderId) {
        OrderMessage poison = OrderMessage.builder()
                .orderId(orderId)
                .amount(-100.0) // 负数金额，触发 JSR-303 校验失败
                .build();
        producer.sendOrder(poison);
        return Result.success("毒丸报文已发射");
    }

    @PostMapping("/order/idempotent")
    public Result<String> sendIdempotent(@RequestBody @Valid OrderMessage order) {
        producer.sendOrder(order);
        return Result.success("防重测试消息已发送: orderId=" + order.getOrderId());
    }
}
