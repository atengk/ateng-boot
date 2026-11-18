package io.github.atengk.boot.pay.spi;

import io.github.atengk.boot.pay.model.callback.OrderClosedCallback;
import io.github.atengk.boot.pay.model.callback.PayCallbackResult;
import io.github.atengk.boot.pay.model.callback.PaymentSuccessCallback;
import io.github.atengk.boot.pay.model.callback.RefundSuccessCallback;
import io.github.atengk.boot.pay.model.callback.TransferNotifyCallback;

/**
 * 支付、退款、关单与转账异步回调业务监听器 SPI
 * <p>
 * 业务方通过实现此接口并注册为 Spring Bean，即可消费经过签名验证、解密与并发防重拦截后的统一标准化回调事件。
 * 新增的 {@link #onOrderClosed} 与 {@link #onTransferNotify} 均提供向后兼容的 {@code default} 实现，
 * 既有仅处理支付/退款回调的实现类无需任何修改即可平滑升级。
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

    /**
     * 判断当前监听器是否支持处理指定的订单关闭回调事件
     *
     * @param callback 标准化订单关闭回调事件
     * @return true 表示支持处理，false 表示跳过
     */
    default boolean supports(OrderClosedCallback callback) {
        return true;
    }

    /**
     * 处理订单关闭回调事件
     *
     * @param callback 标准化订单关闭回调事件
     * @return 回调处理结果指示器（默认返回成功 ACK，确保向后兼容）
     */
    default PayCallbackResult onOrderClosed(OrderClosedCallback callback) {
        return PayCallbackResult.success();
    }

    /**
     * 判断当前监听器是否支持处理指定的商家转账结果异步回调事件
     *
     * @param callback 标准化商家转账异步回调事件
     * @return true 表示支持处理，false 表示跳过
     */
    default boolean supports(TransferNotifyCallback callback) {
        return true;
    }

    /**
     * 处理商家转账结果异步回调事件
     *
     * @param callback 标准化商家转账异步回调事件
     * @return 回调处理结果指示器（默认返回成功 ACK，确保向后兼容）
     */
    default PayCallbackResult onTransferNotify(TransferNotifyCallback callback) {
        return PayCallbackResult.success();
    }
}
