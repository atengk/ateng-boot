package io.github.atengk.boot.pay.core;

import io.github.atengk.boot.pay.core.context.PayContextHolder;
import io.github.atengk.boot.pay.enums.PayChannel;
import io.github.atengk.boot.pay.enums.PayResultCode;
import io.github.atengk.boot.pay.enums.PayTradeState;
import io.github.atengk.boot.pay.enums.PayTradeType;
import io.github.atengk.boot.pay.exception.PayException;
import io.github.atengk.boot.pay.model.request.*;
import io.github.atengk.boot.pay.model.response.PayResponse;
import io.github.atengk.boot.pay.model.response.WechatAppParams;
import io.github.atengk.boot.pay.model.response.WechatJsapiParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 顶层轻量统一收单门面 PayTemplate 单元测试
 * <p>
 * 验证：
 * 1. 微信与支付宝双渠道智能分发与全场景路由；
 * 2. 扁平直达凭据出参完整性（qrCode, payUrl, formHtml, wechatJsapi, orderString）；
 * 3. 查单与关单状态机透传与单标识符重载；
 * 4. 金额小数位精度强校验与归一化（严禁超过 2 位分）；
 * 5. 线程/租户上下文 PayContextHolder 渠道级联回退；
 * 6. 渠道未启用或不支持场景的安全拦截与逃生舱。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@ExtendWith(MockitoExtension.class)
class PayTemplateTest {

    @Mock
    private WechatPayTemplate wechatPayTemplate;

    @Mock
    private AlipayTemplate alipayTemplate;

    private PayTemplate payTemplate;

    @BeforeEach
    void setUp() {
        payTemplate = new PayTemplate(wechatPayTemplate, alipayTemplate);
        PayContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        PayContextHolder.clear();
    }

    // =========================================================================
    // 1. 微信支付全场景收单路由与直达凭据消费
    // =========================================================================

    @Test
    @DisplayName("微信 Native 扫码支付: 成功路由并扁平直达 qrCode")
    void testPay_wechatNative() {
        PayResponse expected = PayResponse.builder()
                .channel(PayChannel.WECHAT_PAY)
                .tradeType(PayTradeType.NATIVE)
                .outTradeNo("WX_NATIVE_1001")
                .qrCode("weixin://wxpay/bizpayurl?pr=abcdef")
                .tradeState(PayTradeState.NOTPAY)
                .success(true)
                .build();
        when(wechatPayTemplate.payNative(any(WechatPayRequest.class))).thenReturn(expected);

        UnifiedPayRequest request = UnifiedPayRequest.builder()
                .channel(PayChannel.WECHAT_PAY)
                .tradeType(PayTradeType.NATIVE)
                .outTradeNo("WX_NATIVE_1001")
                .subject("微信扫码商品")
                .amount(new BigDecimal("10.00"))
                .build();

        PayResponse response = payTemplate.pay(request);

        assertNotNull(response);
        assertEquals(PayChannel.WECHAT_PAY, response.getChannel());
        assertEquals("weixin://wxpay/bizpayurl?pr=abcdef", response.getQrCode());
        assertTrue(response.isSuccess());

        ArgumentCaptor<WechatPayRequest> captor = ArgumentCaptor.forClass(WechatPayRequest.class);
        verify(wechatPayTemplate).payNative(captor.capture());
        assertEquals("WX_NATIVE_1001", captor.getValue().getOutTradeNo());
        assertEquals("微信扫码商品", captor.getValue().getDescription());
        assertEquals(new BigDecimal("10.00"), captor.getValue().getAmount());
    }

    @Test
    @DisplayName("微信 JSAPI 小程序/公众号支付: 成功路由并扁平直达 wechatJsapi 5 元素调起凭据")
    void testPay_wechatJsapi() {
        WechatJsapiParams jsapiParams = WechatJsapiParams.builder()
                .appId("wx_app_123")
                .timeStamp("1710000000")
                .nonceStr("randomNonce")
                .packageVal("prepay_id=wx20261023000000")
                .signType("RSA")
                .paySign("base64PaySign")
                .build();

        PayResponse expected = PayResponse.builder()
                .channel(PayChannel.WECHAT_PAY)
                .tradeType(PayTradeType.JSAPI)
                .outTradeNo("WX_JSAPI_1002")
                .wechatJsapi(jsapiParams)
                .tradeState(PayTradeState.NOTPAY)
                .success(true)
                .build();
        when(wechatPayTemplate.payJsapi(any(WechatPayRequest.class))).thenReturn(expected);

        UnifiedPayRequest request = UnifiedPayRequest.builder()
                .channel(PayChannel.WECHAT_PAY)
                .tradeType(PayTradeType.JSAPI)
                .outTradeNo("WX_JSAPI_1002")
                .subject("小程序会员充值")
                .amount(new BigDecimal("99.90"))
                .openid("oUpF8uMuAJO_M2pxb1Q9zNjWeS6o")
                .build();

        PayResponse response = payTemplate.pay(request);

        assertNotNull(response);
        assertNotNull(response.getWechatJsapi());
        assertEquals("wx_app_123", response.getWechatJsapi().getAppId());
        assertEquals("prepay_id=wx20261023000000", response.getWechatJsapi().getPackage());
        assertEquals("base64PaySign", response.getWechatJsapi().getPaySign());

        verify(wechatPayTemplate).payJsapi(any(WechatPayRequest.class));
    }

    @Test
    @DisplayName("微信 H5 手机网页支付: 成功路由并扁平直达 payUrl")
    void testPay_wechatH5() {
        PayResponse expected = PayResponse.builder()
                .channel(PayChannel.WECHAT_PAY)
                .tradeType(PayTradeType.H5)
                .outTradeNo("WX_H5_1003")
                .payUrl("https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?prepay_id=wx123")
                .tradeState(PayTradeState.NOTPAY)
                .success(true)
                .build();
        when(wechatPayTemplate.payH5(any(WechatPayRequest.class))).thenReturn(expected);

        UnifiedPayRequest request = UnifiedPayRequest.builder()
                .channel(PayChannel.WECHAT_PAY)
                .tradeType(PayTradeType.H5)
                .outTradeNo("WX_H5_1003")
                .subject("H5网页购书")
                .amount(new BigDecimal("45.00"))
                .build();

        PayResponse response = payTemplate.pay(request);

        assertNotNull(response);
        assertEquals("https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?prepay_id=wx123", response.getPayUrl());
        verify(wechatPayTemplate).payH5(any(WechatPayRequest.class));
    }

    @Test
    @DisplayName("微信 App 客户端支付: 成功路由并扁平直达 wechatApp 调起凭据")
    void testPay_wechatApp() {
        WechatAppParams appParams = WechatAppParams.builder()
                .appid("wx_app_open")
                .partnerid("1900000109")
                .prepayid("wx20261023app123")
                .packageVal("Sign=WXPay")
                .noncestr("nonce123")
                .timestamp("1710000001")
                .sign("appSign")
                .build();

        PayResponse expected = PayResponse.builder()
                .channel(PayChannel.WECHAT_PAY)
                .tradeType(PayTradeType.APP)
                .outTradeNo("WX_APP_1004")
                .wechatApp(appParams)
                .tradeState(PayTradeState.NOTPAY)
                .success(true)
                .build();
        when(wechatPayTemplate.payApp(any(WechatPayRequest.class))).thenReturn(expected);

        UnifiedPayRequest request = UnifiedPayRequest.builder()
                .channel(PayChannel.WECHAT_PAY)
                .tradeType(PayTradeType.APP)
                .outTradeNo("WX_APP_1004")
                .subject("App 游戏点券")
                .amount(new BigDecimal("648.00"))
                .build();

        PayResponse response = payTemplate.pay(request);

        assertNotNull(response);
        assertNotNull(response.getWechatApp());
        assertEquals("wx20261023app123", response.getWechatApp().getPrepayid());
        verify(wechatPayTemplate).payApp(any(WechatPayRequest.class));
    }

    @Test
    @DisplayName("微信付款码条码支付: 成功路由 payBarcode")
    void testPay_wechatBarcode() {
        PayResponse expected = PayResponse.builder()
                .channel(PayChannel.WECHAT_PAY)
                .tradeType(PayTradeType.BARCODE)
                .outTradeNo("WX_BARCODE_1005")
                .transactionId("42000021002026102300001")
                .tradeState(PayTradeState.SUCCESS)
                .success(true)
                .build();
        when(wechatPayTemplate.payBarcode(any(WechatPayRequest.class))).thenReturn(expected);

        UnifiedPayRequest request = UnifiedPayRequest.builder()
                .channel(PayChannel.WECHAT_PAY)
                .tradeType(PayTradeType.BARCODE)
                .outTradeNo("WX_BARCODE_1005")
                .subject("便利店条码支付")
                .amount(new BigDecimal("15.50"))
                .authCode("134567890123456789")
                .build();

        PayResponse response = payTemplate.pay(request);

        assertNotNull(response);
        assertEquals(PayTradeState.SUCCESS, response.getTradeState());
        assertEquals("42000021002026102300001", response.getTransactionId());
        verify(wechatPayTemplate).payBarcode(any(WechatPayRequest.class));
    }

    @Test
    @DisplayName("微信收单不支持 PAGE 场景: 抛出 PAY_PARAM_INVALID")
    void testPay_wechatPage_unsupported() {
        UnifiedPayRequest request = UnifiedPayRequest.builder()
                .channel(PayChannel.WECHAT_PAY)
                .tradeType(PayTradeType.PAGE)
                .outTradeNo("WX_PAGE_ERR")
                .subject("PC 网页商品")
                .amount(new BigDecimal("100.00"))
                .build();

        PayException ex = assertThrows(PayException.class, () -> payTemplate.pay(request));
        assertEquals(PayResultCode.PAY_PARAM_INVALID, ex.getResultCode());
        assertTrue(ex.getMessage().contains("微信支付不支持 PAGE"));
    }

    // =========================================================================
    // 2. 支付宝全场景收单路由与直达凭据消费
    // =========================================================================

    @Test
    @DisplayName("支付宝电脑网站支付 payPage: 成功路由并扁平直达 formHtml")
    void testPay_alipayPage() {
        PayResponse expected = PayResponse.builder()
                .channel(PayChannel.ALIPAY)
                .tradeType(PayTradeType.PAGE)
                .outTradeNo("ALI_PAGE_2001")
                .formHtml("<form id=\"alipaysubmit\" action=\"https://openapi.alipay.com/gateway.do\"><script>document.forms[0].submit();</script></form>")
                .tradeState(PayTradeState.NOTPAY)
                .success(true)
                .build();
        when(alipayTemplate.payPage(any(AlipayPayRequest.class))).thenReturn(expected);

        UnifiedPayRequest request = UnifiedPayRequest.builder()
                .channel(PayChannel.ALIPAY)
                .tradeType(PayTradeType.PAGE)
                .outTradeNo("ALI_PAGE_2001")
                .subject("戴尔显示器")
                .amount(new BigDecimal("2999.00"))
                .returnUrl("https://shop.com/return")
                .build();

        PayResponse response = payTemplate.pay(request);

        assertNotNull(response);
        assertEquals(PayChannel.ALIPAY, response.getChannel());
        assertNotNull(response.getFormHtml());
        assertTrue(response.getFormHtml().contains("<form"));

        ArgumentCaptor<AlipayPayRequest> captor = ArgumentCaptor.forClass(AlipayPayRequest.class);
        verify(alipayTemplate).payPage(captor.capture());
        assertEquals("ALI_PAGE_2001", captor.getValue().getOutTradeNo());
        assertEquals("戴尔显示器", captor.getValue().getSubject());
        assertEquals(new BigDecimal("2999.00"), captor.getValue().getAmount());
        assertEquals("https://shop.com/return", captor.getValue().getReturnUrl());
    }

    @Test
    @DisplayName("支付宝手机网站支付 payWap: 映射至 H5 场景并扁平直达 formHtml")
    void testPay_alipayWap() {
        PayResponse expected = PayResponse.builder()
                .channel(PayChannel.ALIPAY)
                .tradeType(PayTradeType.H5)
                .outTradeNo("ALI_WAP_2002")
                .formHtml("<form id=\"wap\"></form>")
                .tradeState(PayTradeState.NOTPAY)
                .success(true)
                .build();
        when(alipayTemplate.payWap(any(AlipayPayRequest.class))).thenReturn(expected);

        UnifiedPayRequest request = UnifiedPayRequest.builder()
                .channel(PayChannel.ALIPAY)
                .tradeType(PayTradeType.H5)
                .outTradeNo("ALI_WAP_2002")
                .subject("手机数码配件")
                .amount(new BigDecimal("199.00"))
                .build();

        PayResponse response = payTemplate.pay(request);

        assertNotNull(response);
        assertEquals(PayTradeType.H5, response.getTradeType());
        assertNotNull(response.getFormHtml());
        verify(alipayTemplate).payWap(any(AlipayPayRequest.class));
    }

    @Test
    @DisplayName("支付宝 App 客户端支付: 成功路由并扁平直达已签名的 orderString")
    void testPay_alipayApp() {
        PayResponse expected = PayResponse.builder()
                .channel(PayChannel.ALIPAY)
                .tradeType(PayTradeType.APP)
                .outTradeNo("ALI_APP_2003")
                .orderString("alipay_sdk=alipay-sdk-java&biz_content={}&sign=sampleSign")
                .tradeState(PayTradeState.NOTPAY)
                .success(true)
                .build();
        when(alipayTemplate.payApp(any(AlipayPayRequest.class))).thenReturn(expected);

        UnifiedPayRequest request = UnifiedPayRequest.builder()
                .channel(PayChannel.ALIPAY)
                .tradeType(PayTradeType.APP)
                .outTradeNo("ALI_APP_2003")
                .subject("App 电商订单")
                .amount(new BigDecimal("350.00"))
                .build();

        PayResponse response = payTemplate.pay(request);

        assertNotNull(response);
        assertEquals(PayTradeType.APP, response.getTradeType());
        assertEquals("alipay_sdk=alipay-sdk-java&biz_content={}&sign=sampleSign", response.getOrderString());
        verify(alipayTemplate).payApp(any(AlipayPayRequest.class));
    }

    @Test
    @DisplayName("支付宝当面付扫码与条码支付: 成功路由 payQr 与 payBarcode")
    void testPay_alipayFaceToFace() {
        // 1. payQr (NATIVE)
        PayResponse qrExpected = PayResponse.builder()
                .channel(PayChannel.ALIPAY)
                .tradeType(PayTradeType.NATIVE)
                .outTradeNo("ALI_QR_2004")
                .qrCode("https://qr.alipay.com/bax123")
                .tradeState(PayTradeState.NOTPAY)
                .success(true)
                .build();
        when(alipayTemplate.payQr(any(AlipayPayRequest.class))).thenReturn(qrExpected);

        UnifiedPayRequest qrReq = UnifiedPayRequest.builder()
                .channel(PayChannel.ALIPAY)
                .tradeType(PayTradeType.NATIVE)
                .outTradeNo("ALI_QR_2004")
                .subject("自动咖啡机")
                .amount(new BigDecimal("12.00"))
                .build();
        PayResponse qrResp = payTemplate.pay(qrReq);
        assertEquals("https://qr.alipay.com/bax123", qrResp.getQrCode());

        // 2. payBarcode (BARCODE)
        PayResponse barExpected = PayResponse.builder()
                .channel(PayChannel.ALIPAY)
                .tradeType(PayTradeType.BARCODE)
                .outTradeNo("ALI_BAR_2005")
                .transactionId("202610232200140088")
                .tradeState(PayTradeState.SUCCESS)
                .success(true)
                .build();
        when(alipayTemplate.payBarcode(any(AlipayPayRequest.class))).thenReturn(barExpected);

        UnifiedPayRequest barReq = UnifiedPayRequest.builder()
                .channel(PayChannel.ALIPAY)
                .tradeType(PayTradeType.BARCODE)
                .outTradeNo("ALI_BAR_2005")
                .subject("收银台扫码")
                .amount(new BigDecimal("66.00"))
                .authCode("281234567890123456")
                .build();
        PayResponse barResp = payTemplate.pay(barReq);
        assertEquals(PayTradeState.SUCCESS, barResp.getTradeState());
        assertEquals("202610232200140088", barResp.getTransactionId());
    }

    // =========================================================================
    // 3. 统一查单 (queryOrder) 与 统一关单 (closeOrder)
    // =========================================================================

    @Test
    @DisplayName("统一查单 queryOrder: 依据渠道路由并支持单标识重载")
    void testQueryOrder_routing() {
        // 1. 微信查单
        PayResponse wxExpected = PayResponse.builder()
                .channel(PayChannel.WECHAT_PAY)
                .outTradeNo("QUERY_WX_001")
                .tradeState(PayTradeState.SUCCESS)
                .build();
        when(wechatPayTemplate.queryOrder(any(WechatPayRequest.class))).thenReturn(wxExpected);

        PayResponse wxResp = payTemplate.queryOrder(PayChannel.WECHAT_PAY, "QUERY_WX_001");
        assertEquals(PayChannel.WECHAT_PAY, wxResp.getChannel());
        assertEquals(PayTradeState.SUCCESS, wxResp.getTradeState());

        // 2. 支付宝查单 (带 merchantKey 重载)
        PayResponse aliExpected = PayResponse.builder()
                .channel(PayChannel.ALIPAY)
                .outTradeNo("QUERY_ALI_002")
                .tradeState(PayTradeState.NOTPAY)
                .build();
        when(alipayTemplate.queryOrder(any(AlipayPayRequest.class))).thenReturn(aliExpected);

        PayResponse aliResp = payTemplate.queryOrder(PayChannel.ALIPAY, "app_custom", "QUERY_ALI_002");
        assertEquals(PayChannel.ALIPAY, aliResp.getChannel());
        assertEquals(PayTradeState.NOTPAY, aliResp.getTradeState());
    }

    @Test
    @DisplayName("统一关单 closeOrder: 依据渠道路由并支持单标识重载")
    void testCloseOrder_routing() {
        // 1. 微信关单
        PayResponse wxExpected = PayResponse.builder()
                .channel(PayChannel.WECHAT_PAY)
                .outTradeNo("CLOSE_WX_001")
                .tradeState(PayTradeState.CLOSED)
                .build();
        when(wechatPayTemplate.closeOrder(any(WechatPayRequest.class))).thenReturn(wxExpected);

        PayResponse wxResp = payTemplate.closeOrder(PayChannel.WECHAT_PAY, "CLOSE_WX_001");
        assertEquals(PayTradeState.CLOSED, wxResp.getTradeState());

        // 2. 支付宝关单 (带 merchantKey 重载)
        PayResponse aliExpected = PayResponse.builder()
                .channel(PayChannel.ALIPAY)
                .outTradeNo("CLOSE_ALI_002")
                .tradeState(PayTradeState.CLOSED)
                .build();
        when(alipayTemplate.closeOrder(any(AlipayPayRequest.class))).thenReturn(aliExpected);

        PayResponse aliResp = payTemplate.closeOrder(PayChannel.ALIPAY, "app_custom", "CLOSE_ALI_002");
        assertEquals(PayTradeState.CLOSED, aliResp.getTradeState());
    }

    // =========================================================================
    // 4. 上下文级联回退与防卫校验 (Context & Fail-Fast)
    // =========================================================================

    @Test
    @DisplayName("线程上下文渠道回退: 当入参未指定渠道时自动从 PayContextHolder 获取")
    void testPay_channelFromContextHolder() {
        PayResponse expected = PayResponse.builder()
                .channel(PayChannel.WECHAT_PAY)
                .tradeType(PayTradeType.NATIVE)
                .outTradeNo("CTX_ORDER_001")
                .qrCode("code_url")
                .build();
        when(wechatPayTemplate.payNative(any(WechatPayRequest.class))).thenReturn(expected);

        UnifiedPayRequest request = UnifiedPayRequest.builder()
                .tradeType(PayTradeType.NATIVE)
                .outTradeNo("CTX_ORDER_001")
                .subject("上下文路由商品")
                .amount(new BigDecimal("10.00"))
                .build();

        // 未设置上下文且未传 channel 时报错
        assertThrows(PayException.class, () -> payTemplate.pay(request));

        // 通过 PayContextHolder 闭包绑定渠道后成功路由
        PayResponse response = PayContextHolder.runWithChannel(PayChannel.WECHAT_PAY, () -> payTemplate.pay(request));
        assertNotNull(response);
        assertEquals(PayChannel.WECHAT_PAY, response.getChannel());
    }

    @Test
    @DisplayName("金额精度防卫: 小数位超过 2 位时抛出 PAY_AMOUNT_INVALID")
    void testAmountPrecision_strictlyValidated() {
        // 超过 2 位有效小数（如 10.001）直接阻断
        UnifiedPayRequest request3Decimals = UnifiedPayRequest.builder()
                .channel(PayChannel.WECHAT_PAY)
                .tradeType(PayTradeType.NATIVE)
                .outTradeNo("DECIMAL_ERR")
                .subject("超精度金额")
                .amount(new BigDecimal("10.001"))
                .build();

        PayException ex = assertThrows(PayException.class, () -> payTemplate.pay(request3Decimals));
        assertEquals(PayResultCode.PAY_AMOUNT_INVALID, ex.getResultCode());
        assertTrue(ex.getMessage().contains("不能超过 2 位"));

        // 末尾多余 0（如 10.500）自动合规归一化为 2 位小数
        when(wechatPayTemplate.payNative(any(WechatPayRequest.class))).thenReturn(PayResponse.builder().build());
        UnifiedPayRequest requestTrailingZero = UnifiedPayRequest.builder()
                .channel(PayChannel.WECHAT_PAY)
                .tradeType(PayTradeType.NATIVE)
                .outTradeNo("NORM_OK")
                .subject("末尾带0金额")
                .amount(new BigDecimal("10.500"))
                .build();

        assertDoesNotThrow(() -> payTemplate.pay(requestTrailingZero));
    }

    @Test
    @DisplayName("参数完整性 Fail-Fast: 空单号、空标题、负金额拦截")
    void testValidationFailFast() {
        // 空入参
        assertThrows(PayException.class, () -> payTemplate.pay(null));

        // 空单号
        assertThrows(PayException.class, () -> payTemplate.pay(UnifiedPayRequest.builder()
                .channel(PayChannel.ALIPAY)
                .tradeType(PayTradeType.PAGE)
                .subject("商品")
                .amount(new BigDecimal("1.00"))
                .build()));

        // 空标题
        assertThrows(PayException.class, () -> payTemplate.pay(UnifiedPayRequest.builder()
                .channel(PayChannel.ALIPAY)
                .tradeType(PayTradeType.PAGE)
                .outTradeNo("ORDER_1")
                .amount(new BigDecimal("1.00"))
                .build()));

        // 负金额
        PayException ex = assertThrows(PayException.class, () -> payTemplate.pay(UnifiedPayRequest.builder()
                .channel(PayChannel.ALIPAY)
                .tradeType(PayTradeType.PAGE)
                .outTradeNo("ORDER_1")
                .subject("商品")
                .amount(new BigDecimal("-1.00"))
                .build()));
        assertEquals(PayResultCode.PAY_AMOUNT_INVALID, ex.getResultCode());
    }

    @Test
    @DisplayName("渠道未装配防卫: 对应渠道门面为 null 时清晰提示")
    void testChannelNotConfigured() {
        PayTemplate onlyAlipayTemplate = new PayTemplate(null, alipayTemplate);

        UnifiedPayRequest wxReq = UnifiedPayRequest.builder()
                .channel(PayChannel.WECHAT_PAY)
                .tradeType(PayTradeType.NATIVE)
                .outTradeNo("WX_UNCONFIGURED")
                .subject("未配置微信")
                .amount(new BigDecimal("10.00"))
                .build();

        PayException ex = assertThrows(PayException.class, () -> onlyAlipayTemplate.pay(wxReq));
        assertEquals(PayResultCode.PAY_GATEWAY_ERROR, ex.getResultCode());
        assertTrue(ex.getMessage().contains("微信支付模块未装配或未启用"));
    }

    @Test
    @DisplayName("专有门面逃生舱验证")
    void testEscapes() {
        assertSame(wechatPayTemplate, payTemplate.getWechatPayTemplate());
        assertSame(alipayTemplate, payTemplate.getAlipayTemplate());
    }
}
