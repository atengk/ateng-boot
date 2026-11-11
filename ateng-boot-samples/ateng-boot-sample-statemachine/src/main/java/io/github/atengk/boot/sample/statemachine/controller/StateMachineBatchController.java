package io.github.atengk.boot.sample.statemachine.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.statemachine.constant.OrderState;
import io.github.atengk.boot.sample.statemachine.dto.OrderBatchCancelRequest;
import io.github.atengk.boot.sample.statemachine.entity.OrderDO;
import io.github.atengk.boot.sample.statemachine.mapper.OrderMapper;
import io.github.atengk.boot.sample.statemachine.service.OrderLifecycleScenarioService;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final OrderLifecycleScenarioService lifecycleService;
    private final OrderMapper orderMapper;

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
            // 自动扫描待支付订单
            List<OrderDO> pendingOrders = orderMapper.selectList(new LambdaQueryWrapper<OrderDO>()
                    .in(OrderDO::getStatus, OrderState.WAIT_PAY, OrderState.PAID, OrderState.WAIT_DELIVER)
                    .last("LIMIT 100"));
            targetIds = pendingOrders.stream().map(OrderDO::getId).collect(Collectors.toList());
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

        // 1. 创建一笔合法 WAIT_PAY 订单
        String validSn = "ORD-BATCH-OK-" + UUID.randomUUID().toString().substring(0, 8);
        OrderDO validOrder = lifecycleService.createOrder(validSn, new BigDecimal("88.00"));

        // 2. 创建一笔不符合 CANCEL 条件的 DELIVERING 订单
        String invalidSn = "ORD-BATCH-FAIL-" + UUID.randomUUID().toString().substring(0, 8);
        OrderDO deliveredOrder = lifecycleService.createOrder(invalidSn, new BigDecimal("168.00"));
        lifecycleService.pay(deliveredOrder.getId());
        lifecycleService.auditRisk(deliveredOrder.getId(), 20, false); // 转为 WAIT_DELIVER
        lifecycleService.ship(deliveredOrder.getId(), "SF_BATCH_TEST"); // 转为 DELIVERING (不可直接 CANCEL)

        List<Long> mixIds = new ArrayList<>();
        mixIds.add(validOrder.getId());
        mixIds.add(deliveredOrder.getId());

        BatchTransitionResult<OrderState, OrderDO> result = batchService.batchCancelExpiredOrders(
                mixIds,
                "容错自验：部分合法取消，部分非法拦截",
                10
        );

        return Result.success(OrderBatchResultVO.fromResult(result));
    }
}
