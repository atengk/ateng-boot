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
 * 统一下单收银请求传输对象
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayCheckoutDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 支付渠道 (WECHAT_PAY, ALIPAY)
     */
    @NotNull(message = "支付渠道不能为空")
    private PayChannel channel;

    /**
     * 商户订单号 (业务唯一单号)
     */
    @NotBlank(message = "商户订单号不能为空")
    private String outTradeNo;

    /**
     * 支付金额 (单位：元，全域强类型 BigDecimal)
     */
    @NotNull(message = "支付金额不能为空")
    @DecimalMin(value = "0.01", message = "支付金额不能小于 0.01 元")
    private BigDecimal amount;

    /**
     * 订单标题 / 商品描述
     */
    @NotBlank(message = "订单标题不能为空")
    private String subject;

    /**
     * 用户唯一标识 (微信 openid，JSAPI 支付场景必填)
     */
    private String openid;

    /**
     * 多商户/多应用别名键（缺省使用 default）
     */
    private String merchantKey;

    /**
     * 异步通知回调地址 (覆盖配置文件默认地址)
     */
    private String notifyUrl;

    /**
     * 同步跳转通知地址 (主要用于支付宝网页跳转)
     */
    private String returnUrl;

    /**
     * 业务附加数据 (原样透传)
     */
    private String attach;
}
