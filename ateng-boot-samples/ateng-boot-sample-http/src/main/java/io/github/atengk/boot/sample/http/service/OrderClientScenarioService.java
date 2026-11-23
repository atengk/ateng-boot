package io.github.atengk.boot.sample.http.service;

import io.github.atengk.boot.sample.http.client.OrderServiceClient;
import io.github.atengk.boot.sample.http.dto.OrderResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 声明式订单服务客户端业务场景服务
 * 演示声明式方法级超时微调 (@HttpTimeout) 与本地优雅降级 (OrderServiceClientFallback)
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderClientScenarioService {

    private final OrderServiceClient orderServiceClient;

    /**
     * 1. 查询订单详情（正常调用，未超时）
     */
    public OrderResponse getOrderById(String orderId) {
        log.info("[OrderClientService] Calling declarative getOrderById for orderId: {}", orderId);
        return orderServiceClient.getOrderById(orderId);
    }

    /**
     * 2. 查询订单状态
     */
    public OrderResponse getOrderStatus(String orderId) {
        log.info("[OrderClientService] Calling declarative getOrderStatus for orderId: {}", orderId);
        return orderServiceClient.getOrderStatus(orderId);
    }

    /**
     * 3. 模拟慢调用触发方法级 @HttpTimeout 超时并自动回退执行本地 Fallback 降级
     */
    public OrderResponse getOrderWithTimeoutFallback(String orderId) {
        String timeoutOrderId = orderId.startsWith("slow-") ? orderId : "slow-" + orderId;
        log.info("[OrderClientService] Triggering timeout fallback call with orderId: {}", timeoutOrderId);
        return orderServiceClient.getOrderById(timeoutOrderId);
    }

    /**
     * 4. 模拟下游服务抛出 5xx 异常自动触发本地 Fallback 降级
     */
    public OrderResponse getOrderWithErrorFallback(String orderId) {
        String failOrderId = orderId.startsWith("fail-") ? orderId : "fail-" + orderId;
        log.info("[OrderClientService] Triggering error fallback call with orderId: {}", failOrderId);
        return orderServiceClient.getOrderById(failOrderId);
    }
}
