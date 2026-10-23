package io.github.atengk.boot.pay.spi;

import io.github.atengk.boot.pay.model.callback.PayCallbackResult;
import io.github.atengk.boot.pay.model.callback.PaymentSuccessCallback;
import io.github.atengk.boot.pay.model.callback.RefundSuccessCallback;

/**
 * 支付与退款异步回调业务监听器 SPI
 * <p>
 * 业务方通过实现此接口并注册为 Spring Bean，即可消费经过签名验证、解密与并发防重拦截后的统一标准化支付/退款回调事件。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
public interface PayCallbackListener {

    /**
     * 判断当前监听器是否支持处理指定的支付成功回调事件
     *
     * @param callback 标准化支付成功回调事件
     * @return true 表示支持处理，false 表示跳过
     */
    default boolean supports(PaymentSuccessCallback callback) {
        return true;
    }

    /**
     * 处理支付成功回调事件
     *
     * @param callback 标准化支付成功回调事件
     * @return 回调处理结果指示器（控制向渠道回复确认、重试或忽略）
     */
    PayCallbackResult onPaymentSuccess(PaymentSuccessCallback callback);

    /**
     * 判断当前监听器是否支持处理指定的退款成功回调事件
     *
     * @param callback 标准化退款成功回调事件
     * @return true 表示支持处理，false 表示跳过
     */
    default boolean supports(RefundSuccessCallback callback) {
        return true;
    }

    /**
     * 处理退款成功回调事件
     *
     * @param callback 标准化退款成功回调事件
     * @return 回调处理结果指示器（默认返回成功 ACK）
     */
    default PayCallbackResult onRefundSuccess(RefundSuccessCallback callback) {
        return PayCallbackResult.success();
    }
}
