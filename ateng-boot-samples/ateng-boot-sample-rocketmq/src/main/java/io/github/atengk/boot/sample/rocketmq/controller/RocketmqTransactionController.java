package io.github.atengk.boot.sample.rocketmq.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.rocketmq.checker.SampleOrderTransactionChecker;
import io.github.atengk.boot.sample.rocketmq.consumer.OrderTransactionConsumer;
import io.github.atengk.boot.sample.rocketmq.dto.OrderProduceResponse;
import io.github.atengk.boot.sample.rocketmq.dto.OrderTransactionRequest;
import io.github.atengk.boot.sample.rocketmq.dto.TransactionStatusVO;
import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import io.github.atengk.boot.sample.rocketmq.service.TransactionOrderScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * RocketMQ 原生 2PC 分布式事务与本地事务感知发信场景控制器
 *
 * <p>提供 2PC 半消息协同投递、本地事务提交后感知发信（sendAfterCommit）、回查器状态审计与消费审计端点。</p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Slf4j
@RestController
@RequestMapping("/sample/rocketmq/transaction")
@RequiredArgsConstructor
public class RocketmqTransactionController {

    private final TransactionOrderScenarioService transactionScenarioService;
    private final SampleOrderTransactionChecker orderTransactionChecker;
    private final OrderTransactionConsumer transactionConsumer;

    /**
     * 1. 发送 2PC 原生分布式事务半消息（支持指定本地执行成功提交或失败回滚）
     *
     * @param request 事务请求参数
     * @return 订单发送响应
     */
    @PostMapping("/half-message")
    public Result<OrderProduceResponse> sendHalfMessage(@Valid @RequestBody OrderTransactionRequest request) {
        OrderMessage order = request.toOrderMessage();
        boolean success = request.getLocalSuccess() == null || request.getLocalSuccess();
        SendReceipt receipt = transactionScenarioService.createOrderWith2PcTransaction(order, success);
        OrderProduceResponse response = OrderProduceResponse.builder()
                .orderId(order.getOrderId())
                .messageId(receipt.getMessageId().toString())
                .tag("tx")
                .status(success ? "2PC_COMMITTED" : "2PC_ROLLED_BACK")
                .build();
        return Result.success(response);
    }

    /**
     * 2. 本地事务感知发信（演示本地事务提交后发信与事务回滚零发信防幽灵消息）
     *
     * @param request 事务请求参数
     * @return 事务执行结果说明
     */
    @PostMapping("/send-after-commit")
    public Result<String> sendAfterCommit(@Valid @RequestBody OrderTransactionRequest request) {
        OrderMessage order = request.toOrderMessage();
        boolean success = request.getLocalSuccess() == null || request.getLocalSuccess();
        if (success) {
            transactionScenarioService.createOrderWithSendAfterCommit(order);
            return Result.success("TX_COMMITTED_AND_SENT");
        } else {
            try {
                transactionScenarioService.createOrderWithRollback(order);
            } catch (IllegalStateException ex) {
                log.warn("[TX-SAMPLE] 捕获预期的本地事务回滚异常: orderId={}, error={}", order.getOrderId(), ex.getMessage());
            }
            return Result.success("TX_ROLLED_BACK_ZERO_SEND");
        }
    }

    /**
     * 3. 查询本地事务回查器与模拟数据库状态
     *
     * @return 回查状态 VO
     */
    @GetMapping("/checker/status")
    public Result<TransactionStatusVO> getCheckerStatus() {
        TransactionStatusVO vo = TransactionStatusVO.builder()
                .checkCount(orderTransactionChecker.getCheckCount().get())
                .committedOrders(orderTransactionChecker.getCommittedOrders())
                .rolledBackOrders(orderTransactionChecker.getRolledBackOrders())
                .localDbOrders(transactionScenarioService.getLocalDbOrders())
                .build();
        return Result.success(vo);
    }

    /**
     * 4. 查询事务主题已接收消费的订单列表
     *
     * @return 已消费订单列表
     */
    @GetMapping("/received")
    public Result<List<OrderMessage>> getReceivedTxOrders() {
        return Result.success(transactionConsumer.getReceivedTxOrders());
    }

    /**
     * 5. 清空事务审计记录与回查器状态
     *
     * @return 空响应
     */
    @DeleteMapping("/clear")
    public Result<Void> clearTransactionRecords() {
        transactionConsumer.clear();
        orderTransactionChecker.clear();
        transactionScenarioService.clear();
        return Result.success();
    }
}
