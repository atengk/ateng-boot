package io.github.atengk.boot.sample.rocketmq.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.validation.ValidList;
import io.github.atengk.boot.sample.rocketmq.consumer.OrderFifoConsumer;
import io.github.atengk.boot.sample.rocketmq.consumer.OrderSqlConsumer;
import io.github.atengk.boot.sample.rocketmq.consumer.OrderStandardConsumer;
import io.github.atengk.boot.sample.rocketmq.dto.BatchProduceResponse;
import io.github.atengk.boot.sample.rocketmq.dto.OrderProduceRequest;
import io.github.atengk.boot.sample.rocketmq.dto.OrderProduceResponse;
import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import io.github.atengk.boot.sample.rocketmq.service.StandardRocketmqScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * RocketMQ 基础与保序消息发送场景控制器
 *
 * <p>提供同步发送、异步发送、单向发送、FIFO 顺序发送、SQL92 属性过滤发送、批量发送与消费审计端点。</p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Slf4j
@RestController
@RequestMapping("/sample/rocketmq/produce")
@RequiredArgsConstructor
public class RocketmqProduceController {

    private final StandardRocketmqScenarioService standardScenarioService;
    private final OrderStandardConsumer standardConsumer;
    private final OrderFifoConsumer fifoConsumer;
    private final OrderSqlConsumer sqlConsumer;

    /**
     * 同步阻塞发送标准订单消息
     *
     * @param request 发送请求参数
     * @return 订单发送统一响应
     */
    @PostMapping("/sync")
    public Result<OrderProduceResponse> sendSync(@Valid @RequestBody OrderProduceRequest request) {
        OrderMessage order = request.toOrderMessage();
        String tag = StringUtils.hasText(request.getTag()) ? request.getTag() : "create";
        SendReceipt receipt = standardScenarioService.sendSync(order, tag);
        OrderProduceResponse response = OrderProduceResponse.builder()
                .orderId(order.getOrderId())
                .messageId(receipt.getMessageId().toString())
                .tag(tag)
                .status("SUCCESS")
                .build();
        return Result.success(response);
    }

    /**
     * 异步非阻塞发送标准订单消息
     *
     * @param request 发送请求参数
     * @return 订单发送统一响应
     */
    @PostMapping("/async")
    public Result<OrderProduceResponse> sendAsync(@Valid @RequestBody OrderProduceRequest request) {
        OrderMessage order = request.toOrderMessage();
        CompletableFuture<SendReceipt> future = standardScenarioService.sendAsync(order);
        SendReceipt receipt = future.join();
        OrderProduceResponse response = OrderProduceResponse.builder()
                .orderId(order.getOrderId())
                .messageId(receipt.getMessageId().toString())
                .tag("create")
                .status("ASYNC_SUCCESS")
                .build();
        return Result.success(response);
    }

    /**
     * 单向极速发送订单埋点消息
     *
     * @param request 发送请求参数
     * @return 空响应
     */
    @PostMapping("/oneway")
    public Result<Void> sendOneway(@Valid @RequestBody OrderProduceRequest request) {
        OrderMessage order = request.toOrderMessage();
        standardScenarioService.sendOneway(order);
        return Result.success();
    }

    /**
     * 局部保序发送 FIFO 顺序订单消息
     *
     * @param request 发送请求参数
     * @return 订单发送统一响应
     */
    @PostMapping("/fifo")
    public Result<OrderProduceResponse> sendFifo(@Valid @RequestBody OrderProduceRequest request) {
        OrderMessage order = request.toOrderMessage();
        String messageGroup = StringUtils.hasText(request.getMessageGroup()) ? request.getMessageGroup() : order.getOrderId();
        SendReceipt receipt = standardScenarioService.sendFifo(order, messageGroup);
        OrderProduceResponse response = OrderProduceResponse.builder()
                .orderId(order.getOrderId())
                .messageId(receipt.getMessageId().toString())
                .tag("fifo")
                .status("FIFO_SUCCESS")
                .build();
        return Result.success(response);
    }

    /**
     * 发送携带自定义属性支持 SQL92 服务端过滤的订单消息
     *
     * @param request 发送请求参数
     * @return 订单发送统一响应
     */
    @PostMapping("/sql")
    public Result<OrderProduceResponse> sendSqlFiltered(@Valid @RequestBody OrderProduceRequest request) {
        OrderMessage order = request.toOrderMessage();
        SendReceipt receipt = standardScenarioService.sendSqlFiltered(order, order.getAmount());
        OrderProduceResponse response = OrderProduceResponse.builder()
                .orderId(order.getOrderId())
                .messageId(receipt.getMessageId().toString())
                .tag("sql")
                .status("SQL_SUCCESS")
                .build();
        return Result.success(response);
    }

    /**
     * 批量可靠发送订单消息（强制接入 ValidList 工业级校验）
     *
     * @param requests 批量发送请求列表
     * @return 批量发送统一响应
     */
    @PostMapping("/batch")
    public Result<BatchProduceResponse> sendBatch(@Valid @RequestBody ValidList<OrderProduceRequest> requests) {
        List<OrderMessage> orders = requests.stream().map(OrderProduceRequest::toOrderMessage).toList();
        List<SendReceipt> receipts = standardScenarioService.sendBatch(orders, "batch");
        List<String> orderIds = orders.stream().map(OrderMessage::getOrderId).toList();
        List<String> messageIds = receipts.stream().map(r -> r.getMessageId().toString()).toList();
        BatchProduceResponse response = BatchProduceResponse.builder()
                .total(requests.size())
                .successCount(receipts.size())
                .orderIds(orderIds)
                .messageIds(messageIds)
                .build();
        return Result.success(response);
    }

    /**
     * 查询当前所有基础投递场景（标准、FIFO、SQL92）已消费的订单列表
     *
     * @return 消费记录列表
     */
    @GetMapping("/received")
    public Result<List<OrderMessage>> getReceivedOrders() {
        List<OrderMessage> all = new ArrayList<>();
        all.addAll(standardConsumer.getReceivedOrders());
        all.addAll(fifoConsumer.getReceivedFifoOrders());
        all.addAll(sqlConsumer.getReceivedSqlOrders());
        return Result.success(all);
    }

    /**
     * 清空所有基础场景的消费审计记录
     *
     * @return 空响应
     */
    @DeleteMapping("/clear")
    public Result<Void> clearReceivedOrders() {
        standardConsumer.clear();
        fifoConsumer.clear();
        sqlConsumer.clear();
        return Result.success();
    }
}
