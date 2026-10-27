package io.github.atengk.boot.sample.redis.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单 Stream 消息实体模型
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderStreamEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单编号
     */
    private String orderId;

    /**
     * 订单金额
     */
    private BigDecimal amount;

    /**
     * 事件类型 (CREATED, PAID, CANCELLED)
     */
    private String eventType;

    /**
     * 时间戳
     */
    private Long timestamp;
}
