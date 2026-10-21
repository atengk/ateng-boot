package io.github.atengk.boot.sample.rabbitmq.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单创建业务事件消息
 *
 * @author Ateng
 * @since 2026-10-21
 */
public record OrderCreateMessage(
        String orderId,
        BigDecimal amount,
        String userId,
        LocalDateTime createTime
) {
}
