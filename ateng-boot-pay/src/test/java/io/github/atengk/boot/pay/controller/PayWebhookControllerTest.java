package io.github.atengk.boot.pay.controller;

import io.github.atengk.boot.pay.core.callback.PayCallbackHandler;
import io.github.atengk.boot.pay.enums.PayChannel;
import io.github.atengk.boot.pay.model.callback.PayCallbackResponse;
import io.github.atengk.boot.pay.model.callback.PayCallbackResult;
import io.github.atengk.boot.pay.model.callback.PaymentSuccessCallback;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 支付 Webhook 回调统配端点控制器单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@ExtendWith(MockitoExtension.class)
class PayWebhookControllerTest {

    @Mock
    private PayCallbackHandler callbackHandler;

    @Mock
    private HttpServletRequest request;

    private PayWebhookController controller;

    @BeforeEach
    void setUp() {
        controller = new PayWebhookController(callbackHandler);
    }

    @Test
    @DisplayName("控制器端点: 微信渠道成功分发与 ResponseEntity 转译")
    void testWechatChannelDispatch() {
        PaymentSuccessCallback callback = PaymentSuccessCallback.builder()
                .channel(PayChannel.WECHAT_PAY)
                .outTradeNo("WX_CTRL_101")
                .amount(new BigDecimal("100.00"))
                .build();

        PayCallbackResponse response = PayCallbackResponse.builder()
                .httpStatus(200)
                .contentType("application/json;charset=utf-8")
                .responseBody("{\"code\":\"SUCCESS\",\"message\":\"成功\"}")
                .result(PayCallbackResult.success())
                .callback(callback)
                .build();

        when(callbackHandler.handleWechat(eq("mch_001"), eq(request))).thenReturn(response);

        ResponseEntity<String> result = controller.handleCallback("wechat", "mch_001", request);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("{\"code\":\"SUCCESS\",\"message\":\"成功\"}", result.getBody());
        verify(callbackHandler, times(1)).handleWechat("mch_001", request);
    }

    @Test
    @DisplayName("控制器端点: 微信别名 wxpay 与缺省商户 default 降级路由")
    void testWechatAliasAndDefaultMerchant() {
        PayCallbackResponse response = PayCallbackResponse.builder()
                .httpStatus(200)
                .contentType("application/json;charset=utf-8")
                .responseBody("{\"code\":\"SUCCESS\",\"message\":\"成功\"}")
                .result(PayCallbackResult.success())
                .build();

        when(callbackHandler.handleWechat(eq("default"), eq(request))).thenReturn(response);

        ResponseEntity<String> result = controller.handleCallback("wxpay", null, request);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        verify(callbackHandler, times(1)).handleWechat("default", request);
    }

    @Test
    @DisplayName("控制器端点: 支付宝渠道分发与 ResponseEntity 转译")
    void testAlipayChannelDispatch() {
        PaymentSuccessCallback callback = PaymentSuccessCallback.builder()
                .channel(PayChannel.ALIPAY)
                .outTradeNo("ALI_CTRL_202")
                .amount(new BigDecimal("50.00"))
                .build();

        PayCallbackResponse response = PayCallbackResponse.builder()
                .httpStatus(200)
                .contentType("text/plain;charset=utf-8")
                .responseBody("success")
                .result(PayCallbackResult.success())
                .callback(callback)
                .build();

        when(callbackHandler.handleAlipay(eq("app_002"), eq(request))).thenReturn(response);

        ResponseEntity<String> result = controller.handleCallback("alipay", "app_002", request);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("success", result.getBody());
        verify(callbackHandler, times(1)).handleAlipay("app_002", request);
    }

    @Test
    @DisplayName("控制器端点: 不受支持的渠道安全返回 HTTP 400 Bad Request")
    void testUnsupportedChannel() {
        ResponseEntity<String> result = controller.handleCallback("unionpay", "mch_999", request);

        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
        assertTrue(result.getBody().contains("Unsupported payment callback channel"));
        verifyNoInteractions(callbackHandler);
    }
}
