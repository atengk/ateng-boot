package io.github.atengk.boot.sample.kafka.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单业务传输实体
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderMessage implements Serializable {

    private String orderId;
    private BigDecimal amount;
    private String userId;
    private String tenantId;
}
