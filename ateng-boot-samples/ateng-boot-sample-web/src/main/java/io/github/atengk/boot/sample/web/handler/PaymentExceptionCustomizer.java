package io.github.atengk.boot.sample.web.handler;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.web.exception.PaymentChannelException;
import io.github.atengk.boot.web.handler.GlobalExceptionCustomizer;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 支付通道异常自定义转译器
 * 实现 GlobalExceptionCustomizer SPI，优先拦截 PaymentChannelException 并转译为统一业务响应契约
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Component
@Order(10)
public class PaymentExceptionCustomizer implements GlobalExceptionCustomizer {

    @Override
    public Result<?> customize(Throwable throwable) {
        if (throwable instanceof PaymentChannelException pce) {
            // 转译为自定义业务错误响应：业务状态码 40020
            return Result.fail(40020, "支付通道异常 [" + pce.getChannelCode() + "]: " + pce.getMessage());
        }
        return null;
    }
}
