package io.github.atengk.boot.sample.datasource.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.datasource.dto.OrderCreateRequest;
import io.github.atengk.boot.sample.datasource.service.OrderService;
import io.github.atengk.boot.sample.datasource.vo.OrderResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 订单管理控制器
 * <p>
 * 严格遵循显式 Result 契约与传输对象隔离规范。
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Slf4j
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * 租户透明切库下单
     *
     * @param request 订单创建请求 (JSR-303 格式校验)
     * @return 订单展示视图强类型结果
     */
    @PostMapping("/tenant")
    public Result<OrderResponse> createTenantOrder(@Valid @RequestBody OrderCreateRequest request) {
        OrderResponse response = orderService.createTenantOrder(request);
        return Result.success(response);
    }

    /**
     * 租户透明查询订单
     *
     * @param orderNo 业务订单号
     * @return 订单展示视图强类型结果
     */
    @GetMapping("/tenant/{orderNo}")
    public Result<OrderResponse> getTenantOrder(@PathVariable String orderNo) {
        OrderResponse response = orderService.getTenantOrder(orderNo);
        return Result.success(response);
    }

    /**
     * 主库下单并激活写后读主防延迟
     *
     * @param request 订单创建请求
     * @return 订单展示视图强类型结果
     */
    @PostMapping("/master")
    public Result<OrderResponse> createMasterOrder(@Valid @RequestBody OrderCreateRequest request) {
        OrderResponse response = orderService.createMasterOrderWithPostWrite(request);
        return Result.success(response);
    }

    /**
     * 只读事务读写分离查询订单 (写后读主窗口期内自动回退到 master)
     *
     * @param orderNo 业务订单号
     * @return 订单展示视图强类型结果
     */
    @GetMapping("/read-write/{orderNo}")
    public Result<OrderResponse> getOrderReadWrite(@PathVariable String orderNo) {
        OrderResponse response = orderService.getOrderReadWrite(orderNo);
        return Result.success(response);
    }
}
