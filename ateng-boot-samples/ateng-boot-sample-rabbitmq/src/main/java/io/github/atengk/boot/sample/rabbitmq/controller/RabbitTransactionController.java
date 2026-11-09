package io.github.atengk.boot.sample.rabbitmq.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.rabbitmq.consumer.OrderConsumer;
import io.github.atengk.boot.sample.rabbitmq.dto.OrderCreateRequest;
import io.github.atengk.boot.sample.rabbitmq.model.OrderCreateMessage;
import io.github.atengk.boot.sample.rabbitmq.service.OrderTransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 事务感知发信与幽灵消息防卫体验控制器
 * 演示 sendAfterCommit 在本地数据库事务提交后发信与事务回滚时零发信
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Slf4j
@RestController
@RequestMapping("/sample/rabbitmq/transaction")
@RequiredArgsConstructor
public class RabbitTransactionController {

    private final OrderTransactionService transactionService;
    private final OrderConsumer orderConsumer;

    /**
     * 事务提交成功发信 (Commit 后物理投递)
     *
     * @param request 订单请求
     * @return 订单ID
     */
    @PostMapping("/commit")
    public Result<String> createOrderWithCommit(@Valid @RequestBody OrderCreateRequest request) {
        transactionService.createOrderWithCommit(request.toMessage());
        return Result.success(request.getOrderId(), "本地事务提交成功，触发物理网络发信");
    }

    /**
     * 事务异常回滚发信 (Rollback 触发零发信，杜绝幽灵消息)
     *
     * @param request 订单请求
     * @return 响应说明
     */
    @PostMapping("/rollback")
    public Result<String> createOrderWithRollback(@Valid @RequestBody OrderCreateRequest request) {
        try {
            transactionService.createOrderWithRollback(request.toMessage());
        } catch (IllegalStateException ex) {
            log.warn("[TX-ROLLBACK] 捕获预期本地事务异常: {}", ex.getMessage());
        }
        return Result.success(request.getOrderId(), "本地事务已回滚，发信钩子已自动作废（幽灵消息防御生效）");
    }

    /**
     * 探查事务场景下成功消费的订单列表
     *
     * @return 订单消息列表
     */
    @GetMapping("/records")
    public Result<List<OrderCreateMessage>> getTransactionOrders() {
        return Result.success(orderConsumer.getReceivedMessages());
    }

    /**
     * 查询本地事务落库尝试计数
     *
     * @return 计数值
     */
    @GetMapping("/db-count")
    public Result<Integer> getDbRecordCount() {
        return Result.success(transactionService.getDbRecordCount());
    }
}
