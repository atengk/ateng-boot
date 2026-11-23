package io.github.atengk.boot.sample.pay.dto;

import io.github.atengk.boot.pay.enums.PayChannel;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 线下收银台条码付执行传输对象
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayBarcodeDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "支付渠道不能为空")
    private PayChannel channel;

    private String merchantKey;

    @NotBlank(message = "商户订单号不能为空")
    private String outTradeNo;

    @NotNull(message = "支付金额不能为空")
    @DecimalMin(value = "0.01", message = "支付金额必须大于 0 元")
    private BigDecimal amount;

    @NotBlank(message = "付款码授权码不能为空")
    private String authCode;

    @NotBlank(message = "订单标题不能为空")
    private String subject;

    /**
     * 自定义最长轮询等待时间 (可选，默认使用系统配置)
     */
    private java.time.Duration maxWaitTime;

    /**
     * 自定义轮询间隔时间 (可选，默认使用系统配置)
     */
    private java.time.Duration pollInterval;

    /**
     * 超时是否自动撤销冲正 (可选，默认使用系统配置)
     */
    private Boolean autoCancelOnTimeout;
}
