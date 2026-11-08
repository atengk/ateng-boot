package io.github.atengk.boot.sample.redis.model.idempotent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 支付回调处理结果视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentCallbackVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String paymentNo;
    private String orderId;
    private Boolean processed;
    private String message;
}
