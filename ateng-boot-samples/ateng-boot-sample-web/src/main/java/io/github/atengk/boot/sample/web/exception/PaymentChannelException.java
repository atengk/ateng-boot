package io.github.atengk.boot.sample.web.exception;

/**
 * 支付通道受控业务异常
 * 用于演示第三方支付通道异常时，借助 GlobalExceptionCustomizer SPI 自定义扩展转译为领域错误响应
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class PaymentChannelException extends RuntimeException {

    private final String channelCode;

    public PaymentChannelException(String channelCode, String message) {
        super(message);
        this.channelCode = channelCode;
    }

    public String getChannelCode() {
        return channelCode;
    }
}
