package io.github.atengk.boot.sample.http.client;

import io.github.atengk.boot.http.annotation.HttpClient;
import io.github.atengk.boot.http.annotation.HttpTimeout;
import io.github.atengk.boot.sample.http.dto.OrderResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * 订单服务声明式客户端
 * 演示方法级 @HttpTimeout 超时微调与 @HttpClient(fallback = ...) 本地容错降级
 *
 * @author Ateng
 * @since 2026-10-29
 */
@HttpClient(
        name = "order-service",
        url = "${sample.order-service.url:http://localhost:8080}",
        fallback = OrderServiceClientFallback.class
)
@HttpExchange
public interface OrderServiceClient {

    /**
     * 查询订单详情，挂载方法级专用超时注解覆盖全局默认超时
     *
     * @param orderId 订单标识
     * @return 订单详情响应对象
     */
    @GetExchange("/orders/{orderId}")
    @HttpTimeout(readTimeout = "300ms", connectTimeout = "1s")
    OrderResponse getOrderById(@PathVariable("orderId") String orderId);

    /**
     * 查询订单状态，采用全局客户端默认超时
     *
     * @param orderId 订单标识
     * @return 订单详情响应对象
     */
    @GetExchange("/orders/{orderId}/status")
    OrderResponse getOrderStatus(@PathVariable("orderId") String orderId);
}
