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
 * SaaS 物理多租户分库业务控制器
 * <p>
 * 演示请求头携带 X-Tenant-Id 透明路由物理分库下单、隔离查询与跨租户数据防穿透。
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@RestController
@RequestMapping("/sample/datasource/tenant")
@RequiredArgsConstructor
public class DatasourceTenantController {

    private final OrderService orderService;

    /**
     * 1. 租户透明切库下单
     * <p>
     * 自动感知请求头 X-Tenant-Id 并路由至对应租户独立数据库。
     *
     * @param request 订单创建请求
     * @return 订单展示强类型结果
     */
    @PostMapping("/order")
    public Result<OrderResponse> createTenantOrder(@Valid @RequestBody OrderCreateRequest request) {
        OrderResponse response = orderService.createTenantOrder(request);
        return Result.success(response);
    }

    /**
     * 2. 租户透明查询订单
     * <p>
     * 依据当前请求的租户上下文读取订单；若跨租户查询则返回空数据。
     *
     * @param orderNo 业务订单号
     * @return 订单展示强类型结果
     */
    @GetMapping("/order/{orderNo}")
    public Result<OrderResponse> getTenantOrder(@PathVariable String orderNo) {
        OrderResponse response = orderService.getTenantOrder(orderNo);
        return Result.success(response);
    }
}
