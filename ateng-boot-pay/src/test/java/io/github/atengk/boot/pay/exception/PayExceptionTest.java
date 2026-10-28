package io.github.atengk.boot.pay.exception;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.pay.enums.PayResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 支付异常体系单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
class PayExceptionTest {

    @Test
    @DisplayName("PayException 继承体系与状态码测试")
    void testPayExceptionHierarchy() {
        PayException ex = new PayException(PayResultCode.PAY_GATEWAY_ERROR, "网关超时");
        assertThat(ex).isInstanceOf(BusinessException.class);
        assertThat(ex.getResultCode()).isEqualTo(PayResultCode.PAY_GATEWAY_ERROR);
        assertThat(ex.getCode()).isEqualTo(58007);
        assertThat(ex.getMessage()).isEqualTo("网关超时");
    }

    @Test
    @DisplayName("PaySignException 验签异常测试")
    void testPaySignException() {
        PaySignException ex = new PaySignException("签名不匹配");
        assertThat(ex.getResultCode()).isEqualTo(PayResultCode.PAY_SIGN_VERIFY_ERROR);
        assertThat(ex.getCode()).isEqualTo(58001);
    }

    @Test
    @DisplayName("PayDecryptException 密文解密异常测试")
    void testPayDecryptException() {
        PayDecryptException ex = new PayDecryptException("AEAD 解密失败");
        assertThat(ex.getResultCode()).isEqualTo(PayResultCode.PAY_DECRYPT_ERROR);
        assertThat(ex.getCode()).isEqualTo(58002);
    }

    @Test
    @DisplayName("PayNetworkException 网络通信异常与因果链保留测试")
    void testPayNetworkException() {
        RuntimeException cause = new RuntimeException("Connection refused");
        PayNetworkException ex = new PayNetworkException("网络通信失败", cause);
        assertThat(ex.getResultCode()).isEqualTo(PayResultCode.PAY_GATEWAY_ERROR);
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("PayTradeException 原始渠道错误码与消息保留测试")
    void testPayTradeException() {
        PayTradeException ex = new PayTradeException("ORDERPAID", "该订单已支付");
        assertThat(ex.getRawErrorCode()).isEqualTo("ORDERPAID");
        assertThat(ex.getRawErrorMessage()).isEqualTo("该订单已支付");
        assertThat(ex.getResultCode()).isEqualTo(PayResultCode.PAY_TRADE_STATE_INVALID);
        assertThat(ex.getMessage()).contains("ORDERPAID").contains("该订单已支付");
    }
}
