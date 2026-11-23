package io.github.atengk.boot.sample.http.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.http.dto.OrderResponse;
import io.github.atengk.boot.sample.http.dto.UserRequest;
import io.github.atengk.boot.sample.http.dto.UserResponse;
import io.github.atengk.boot.sample.http.service.OrderClientScenarioService;
import io.github.atengk.boot.sample.http.service.UserClientScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 声明式 HTTP 客户端业务控制器
 * 提供基于 @EnableHttpInterfaces 与 @HttpClient 的强类型接口 RPC 调用、方法级超时微调与本地优雅降级体验
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Slf4j
@RestController
@RequestMapping("/sample/http/declarative")
@RequiredArgsConstructor
public class HttpDeclarativeClientController {

    private final UserClientScenarioService userClientScenarioService;
    private final OrderClientScenarioService orderClientScenarioService;

    /**
     * 1. 声明式 GET 查询用户详情
     */
    @GetMapping("/user/{id}")
    public Result<UserResponse> getUserById(@PathVariable("id") Long id) {
        UserResponse response = userClientScenarioService.getUserById(id);
        return Result.success(response);
    }

    /**
     * 2. 声明式 POST 创建用户资源
     */
    @PostMapping("/user")
    public Result<UserResponse> createUser(@Valid @RequestBody UserRequest request) {
        UserResponse response = userClientScenarioService.createUser(request);
        return Result.success(response);
    }

    /**
     * 3. 声明式查询订单详情（正常调用，方法级 @HttpTimeout 守卫未超时）
     */
    @GetMapping("/order/{orderId}")
    public Result<OrderResponse> getOrderById(@PathVariable("orderId") String orderId) {
        OrderResponse response = orderClientScenarioService.getOrderById(orderId);
        return Result.success(response);
    }

    /**
     * 4. 声明式查询订单状态
     */
    @GetMapping("/order/{orderId}/status")
    public Result<OrderResponse> getOrderStatus(@PathVariable("orderId") String orderId) {
        OrderResponse response = orderClientScenarioService.getOrderStatus(orderId);
        return Result.success(response);
    }

    /**
     * 5. 声明式方法级超时触发本地优雅降级 (@HttpClient.fallback)
     */
    @GetMapping("/order/fallback/timeout/{orderId}")
    public Result<OrderResponse> getOrderWithTimeoutFallback(@PathVariable("orderId") String orderId) {
        OrderResponse response = orderClientScenarioService.getOrderWithTimeoutFallback(orderId);
        return Result.success(response);
    }

    /**
     * 6. 声明式下游异常触发本地优雅降级 (@HttpClient.fallback)
     */
    @GetMapping("/order/fallback/error/{orderId}")
    public Result<OrderResponse> getOrderWithErrorFallback(@PathVariable("orderId") String orderId) {
        OrderResponse response = orderClientScenarioService.getOrderWithErrorFallback(orderId);
        return Result.success(response);
    }
}
