package io.github.atengk.boot.sample.statemachine.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.statemachine.constant.OrderEvent;
import io.github.atengk.boot.sample.statemachine.constant.OrderState;
import io.github.atengk.boot.sample.statemachine.dto.OrderCreateRequest;
import io.github.atengk.boot.sample.statemachine.dto.OrderTrackingRequest;
import io.github.atengk.boot.sample.statemachine.dto.OrderTransitionRequest;
import io.github.atengk.boot.sample.statemachine.service.OrderService;
import io.github.atengk.boot.sample.statemachine.vo.OrderVO;
import io.github.atengk.boot.statemachine.model.TransitionResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 电商订单状态机场景控制层（显式强类型契约与零实体泄露）
 *
 * @author Ateng
 * @since 2026-10-25
 */
@RestController
@RequestMapping("/orders")
public class OrderStateMachineController {

    private final OrderService orderService;

    public OrderStateMachineController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 创建订单
     */
    @PostMapping
    public Result<OrderVO> createOrder(@Valid @RequestBody OrderCreateRequest request) {
        return Result.success(orderService.createOrder(request));
    }

    /**
     * 获取订单详情
     */
    @GetMapping("/{id}")
    public Result<OrderVO> getOrder(@PathVariable("id") Long id) {
        return Result.success(orderService.getOrder(id));
    }

    /**
     * 触发订单状态机流转跃迁
     */
    @PostMapping("/{id}/transitions")
    public Result<TransitionResult<OrderState>> fireTransition(
            @PathVariable("id") Long id,
            @Valid @RequestBody OrderTransitionRequest request) {
        return Result.success(orderService.processTransition(id, request));
    }

    /**
     * 追加物流追踪动态（内部自跃迁）
     */
    @PostMapping("/{id}/tracking")
    public Result<TransitionResult<OrderState>> addTracking(
            @PathVariable("id") Long id,
            @Valid @RequestBody OrderTrackingRequest request) {
        return Result.success(orderService.addTrackingInfo(id, request));
    }

    /**
     * 导出订单状态机 Mermaid 语法图例文本
     */
    @GetMapping("/statemachine/mermaid")
    public Result<String> exportMermaid() {
        return Result.success(orderService.exportMermaidDiagram());
    }
}
