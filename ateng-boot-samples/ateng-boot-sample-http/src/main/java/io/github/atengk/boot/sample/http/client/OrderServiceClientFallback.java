package io.github.atengk.boot.sample.http.client;

import io.github.atengk.boot.sample.http.dto.OrderResponse;
import org.springframework.stereotype.Component;

/**
 * 订单服务本地降级兜底实现
 * 当远端服务超时、网络中断或触发 5xx 故障时自动介入，返回优雅降级兜底数据
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Component
public class OrderServiceClientFallback implements OrderServiceClient {

    public static final String STATUS_DEGRADED = "DEGRADED";
    public static final String STATUS_UNKNOWN = "UNKNOWN";

    @Override
    public OrderResponse getOrderById(String orderId) {
        return OrderResponse.builder()
                .orderId(orderId)
                .status(STATUS_DEGRADED)
                .message("远端服务异常，已触发本地优雅降级兜底")
                .build();
    }

    @Override
    public OrderResponse getOrderStatus(String orderId) {
        return OrderResponse.builder()
                .orderId(orderId)
                .status(STATUS_UNKNOWN)
                .message("远端订单状态查询降级")
                .build();
    }
}
