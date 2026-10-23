package io.github.atengk.boot.sample.pay.listener;

import io.github.atengk.boot.pay.model.callback.PayCallbackResult;
import io.github.atengk.boot.pay.model.callback.PaymentSuccessCallback;
import io.github.atengk.boot.pay.spi.PayCallbackListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 业务订单支付成功回调监听器
 * <p>
 * 实现 {@link PayCallbackListener} SPI 接口，由 Spring 容器自动纳管并注入 {@code PayCallbackHandler}。
 * 演示在经过底层自动验签、解密与并发防重拦截后的业务幂等落地与确定性渠道 ACK 返回。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
@Component
public class OrderPaymentCallbackListener implements PayCallbackListener {

    /**
     * 模拟业务已处理订单号集合 (用于业务防重幂等校验)
     */
    private final Set<String> processedOrders = ConcurrentHashMap.newKeySet();

    @Override
    public boolean supports(PaymentSuccessCallback callback) {
        return callback != null && callback.getOutTradeNo() != null;
    }

    @Override
    public PayCallbackResult onPaymentSuccess(PaymentSuccessCallback callback) {
        String outTradeNo = callback.getOutTradeNo();
        log.info("Received verified payment success callback: channel={}, outTradeNo={}, transactionId={}, amount={}, payTime={}",
                callback.getChannel(), outTradeNo, callback.getTransactionId(), callback.getAmount(), callback.getPayTime());

        // 1. 业务层防重幂等检查：若订单已经落地处理过，直接返回 IGNORE 静默确认，防止重复处理
        if (processedOrders.contains(outTradeNo)) {
            log.info("Payment order [{}] has already been processed previously, returning IGNORE ACK to channel.", outTradeNo);
            return PayCallbackResult.ignore("订单已处理，忽略重复通知");
        }

        // 2. 模拟业务订单状态流转与更新（如更新订单为 PAID、核销卡券、生成履约单据等）
        processedOrders.add(outTradeNo);
        log.info("Business order [{}] state successfully updated to PAID in database.", outTradeNo);

        // 3. 返回 SUCCESS 指示网关向第三方渠道回复官方成功确认报文
        return PayCallbackResult.success("订单支付状态更新成功");
    }

    /**
     * 获取已处理完成的订单号集合（供测试或监控探针使用）
     *
     * @return 不可变已处理订单号集合
     */
    public Set<String> getProcessedOrders() {
        return Collections.unmodifiableSet(processedOrders);
    }

    /**
     * 清空已处理订单状态（供单元测试在用例之间安全清理）
     */
    public void reset() {
        processedOrders.clear();
    }
}
