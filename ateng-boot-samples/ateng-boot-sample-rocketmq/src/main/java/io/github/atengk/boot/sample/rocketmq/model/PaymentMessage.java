package io.github.atengk.boot.sample.rocketmq.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 支付结算领域业务消息实体
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentMessage {

    private String paymentId;
    private String orderId;
    private Double amount;
}
