package io.github.atengk.boot.sample.statemachine.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.statemachine.constant.OrderState;
import io.github.atengk.boot.sample.statemachine.dto.OrderBatchCancelRequest;
import io.github.atengk.boot.sample.statemachine.entity.OrderDO;
import io.github.atengk.boot.sample.statemachine.service.OrderTimeoutBatchScenarioService;
import io.github.atengk.boot.sample.statemachine.vo.OrderBatchResultVO;
import io.github.atengk.boot.statemachine.model.BatchTransitionResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 大批量切片流转与超时处理控制器
 * <p>
 * 演示 fireEventBatch 切片流转引擎、大批量订单超时自动关单、
 * 结构化 BatchTransitionResult 聚合回执与 Fail-Safe 局部异常容错治理。
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@RestController
@RequestMapping("/sample/statemachine/batch")
@RequiredArgsConstructor
public class StateMachineBatchController {

    private final OrderTimeoutBatchScenarioService batchService;

    /**
     * 1. 批量切片取消超时订单（支持指定 ID 列表或自动扫描待支付订单）
     *
     * @param request 批量关单入参
     * @return 批量执行结果视图对象
     */
    @PostMapping("/cancel-expired")
    public Result<OrderBatchResultVO> cancelExpiredOrders(@Valid @RequestBody(required = false) OrderBatchCancelRequest request) {
        OrderBatchCancelRequest req = (request != null) ? request : new OrderBatchCancelRequest();
        List<Long> targetIds = req.getOrderIds();

        if (targetIds == null || targetIds.isEmpty()) {
            targetIds = batchService.scanPendingOrderIds(100);
        }

        log.info("[BatchController] 触发大批量切片关单: 订单数={}, 批次={}", targetIds.size(), req.getBatchSize());
        BatchTransitionResult<OrderState, OrderDO> result = batchService.batchCancelExpiredOrders(
                targetIds,
                req.getCancelReason(),
                req.getBatchSize() != null ? req.getBatchSize() : 50
        );

        return Result.success(OrderBatchResultVO.fromResult(result));
    }

    /**
     * 2. 模拟切片批处理局部失败容错（部分订单符合条件成功，部分订单状态不匹配失败）
     *
     * @return 批量执行结果视图对象（包含成功列表与失败原因明细）
     */
    @PostMapping("/simulate-partial-failure")
    public Result<OrderBatchResultVO> simulatePartialFailure() {
        log.info("[BatchController] 模拟批处理部分失败容错场景");
        BatchTransitionResult<OrderState, OrderDO> result = batchService.simulatePartialFailure();
        return Result.success(OrderBatchResultVO.fromResult(result));
    }
}
