package io.github.atengk.boot.pay.core;

import com.wechat.pay.java.core.http.DefaultHttpClientBuilder;
import com.wechat.pay.java.core.http.HttpClient;
import com.wechat.pay.java.service.profitsharing.model.AddReceiverRequest;
import com.wechat.pay.java.service.profitsharing.model.AddReceiverResponse;
import com.wechat.pay.java.service.profitsharing.model.CreateOrderReceiver;
import com.wechat.pay.java.service.profitsharing.model.CreateOrderRequest;
import com.wechat.pay.java.service.profitsharing.model.CreateReturnOrderRequest;
import com.wechat.pay.java.service.profitsharing.model.DeleteReceiverRequest;
import com.wechat.pay.java.service.profitsharing.model.DeleteReceiverResponse;
import com.wechat.pay.java.service.profitsharing.model.OrdersEntity;
import com.wechat.pay.java.service.profitsharing.model.QueryMerchantRatioRequest;
import com.wechat.pay.java.service.profitsharing.model.QueryMerchantRatioResponse;
import com.wechat.pay.java.service.profitsharing.model.QueryOrderAmountRequest;
import com.wechat.pay.java.service.profitsharing.model.QueryOrderAmountResponse;
import com.wechat.pay.java.service.profitsharing.model.QueryOrderRequest;
import com.wechat.pay.java.service.profitsharing.model.QueryReturnOrderRequest;
import com.wechat.pay.java.service.profitsharing.model.ReceiverRelationType;
import com.wechat.pay.java.service.profitsharing.model.ReceiverType;
import com.wechat.pay.java.service.profitsharing.model.ReturnOrdersEntity;
import com.wechat.pay.java.service.profitsharing.model.UnfreezeOrderRequest;
import io.github.atengk.boot.pay.config.properties.PayProperties;
import io.github.atengk.boot.pay.config.properties.WechatMerchantProperties;
import io.github.atengk.boot.pay.config.properties.WechatPayProperties;
import io.github.atengk.boot.pay.core.certificate.WechatAutoCertificateManager;
import io.github.atengk.boot.pay.core.merchant.NamedMerchantRegistry;
import io.github.atengk.boot.pay.enums.PayChannel;
import io.github.atengk.boot.pay.enums.PayRefundState;
import io.github.atengk.boot.pay.enums.PayResultCode;
import io.github.atengk.boot.pay.enums.PayTradeState;
import io.github.atengk.boot.pay.enums.PayTradeType;
import io.github.atengk.boot.pay.exception.PayException;
import io.github.atengk.boot.pay.exception.PaySignException;
import io.github.atengk.boot.pay.exception.PayTradeException;
import io.github.atengk.boot.pay.model.request.UnifiedRefundRequest;
import io.github.atengk.boot.pay.model.request.WechatPayRequest;
import io.github.atengk.boot.pay.model.request.WechatRefundRequest;
import io.github.atengk.boot.pay.model.response.PayRefundResponse;
import io.github.atengk.boot.pay.model.response.PayResponse;
import io.github.atengk.boot.pay.util.PayKeyLoader;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 微信支付专有门面 WechatPayTemplate 100% 离线自洽单元测试
 * <p>
 * 基于 OkHttp MockWebServer 拦截所有出站请求，验证：
 * 1. Native / JSAPI / H5 / App / 条码付 5 大场景收单与出站参数组装；
 * 2. 服务端全自动二次加签与 5 元素封包（wechatJsapi、wechatApp）；
 * 3. 查单与关单状态机映射（SUCCESS, USERPAYING, CLOSED 等）；
 * 4. 直连商户与服务商模式（sp_mchid, sub_mchid）动态切源；
 * 5. 异常因果链转译（验签失败、网关错误、参数非法等）。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
class WechatPayTemplateTest {

    private static final String CLASSPATH_CERT = "classpath:certs/test_certificate.pem";
    private static final String CLASSPATH_KEY = "classpath:certs/test_private_key.pem";

    private MockWebServer server;
    private WechatAutoCertificateManager certificateManager;
    private NamedMerchantRegistry merchantRegistry;
    private WechatPayTemplate payTemplate;

    private PrivateKey testPrivateKey;
    private X509Certificate testCert;
    private String testCertSerial;

    @BeforeEach
    void setUp() throws IOException {
        // 1. 启动纯内存 MockWebServer
        server = new MockWebServer();
        server.start();

        // 2. 加载测试公私钥与证书
        testPrivateKey = PayKeyLoader.loadPrivateKey(CLASSPATH_KEY);
        testCert = PayKeyLoader.loadCertificate(CLASSPATH_CERT);
        testCertSerial = PayKeyLoader.getCertificateSerialNumber(testCert);

        // 3. 初始化并注册平台证书缓存
        certificateManager = new WechatAutoCertificateManager(Duration.ofMinutes(10));
        certificateManager.registerCertificates("1900000001", Map.of(testCertSerial, testCert));
        certificateManager.registerCertificates("1900000000", Map.of(testCertSerial, testCert));

        // 4. 配置多商户（直连默认商户 + 服务商模式商户）
        PayProperties payProperties = new PayProperties();
        WechatPayProperties wechat = payProperties.getWechat();
        wechat.setDefaultMerchant("default");

        Map<String, WechatMerchantProperties> merchants = new HashMap<>();

        // 默认直连商户
        WechatMerchantProperties defaultMch = new WechatMerchantProperties();
        defaultMch.setMchId("1900000001");
        defaultMch.setAppId("wx_default_app");
        defaultMch.setMerchantSerialNumber(testCertSerial);
        defaultMch.setPrivateKeyPath(CLASSPATH_KEY);
        defaultMch.setNotifyUrl("https://example.com/pay/callback/wechat");
        merchants.put("default", defaultMch);

        // 服务商特约子商户
        WechatMerchantProperties partnerMch = new WechatMerchantProperties();
        partnerMch.setMchId("1900000000");
        partnerMch.setAppId("wx_sp_app");
        partnerMch.setSpMchId("1900000000");
        partnerMch.setSpAppId("wx_sp_app");
        partnerMch.setSubMchId("1900000002");
        partnerMch.setSubAppId("wx_sub_app");
        partnerMch.setMerchantSerialNumber(testCertSerial);
        partnerMch.setPrivateKeyPath(CLASSPATH_KEY);
        partnerMch.setNotifyUrl("https://example.com/pay/callback/partner");
        merchants.put("partner_tenant", partnerMch);

        wechat.setMerchants(merchants);
        merchantRegistry = new NamedMerchantRegistry(payProperties);

        // 5. 初始化 WechatPayTemplate
        payTemplate = new WechatPayTemplate(merchantRegistry, certificateManager);

        // 6. 注入重定向拦截器，将腾讯官方域名流量 100% 导入本地 MockWebServer
        OkHttpClient redirectOkHttpClient = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request orig = chain.request();
                    HttpUrl newUrl = orig.url().newBuilder()
                            .scheme("http")
                            .host(server.getHostName())
                            .port(server.getPort())
                            .build();
                    return chain.proceed(orig.newBuilder().url(newUrl).build());
                })
                .build();

        HttpClient mockHttpClient = new DefaultHttpClientBuilder()
                .config(payTemplate.getWechatConfig())
                .okHttpClient(redirectOkHttpClient)
                .build();

        payTemplate.setCustomHttpClient(mockHttpClient);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) {
            server.shutdown();
        }
        if (certificateManager != null) {
            certificateManager.destroy();
        }
    }

    // =========================================================================
    // 1. Native 扫码测试
    // =========================================================================

    @Test
    @DisplayName("payNative: 直连商户扫码下单与出站报文校验")
    void testPayNativeDirect() throws Exception {
        String mockResponseJson = "{\"code_url\":\"weixin://wxpay/bizpayurl?pr=mock_native_qr_code\"}";
        server.enqueue(mockResponse(200, mockResponseJson));

        WechatPayRequest request = WechatPayRequest.builder()
                .outTradeNo("NATIVE_20261023_001")
                .description("测试商品-Native")
                .amount(new BigDecimal("99.90"))
                .build();

        PayResponse response = payTemplate.payNative(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals(PayChannel.WECHAT_PAY, response.getChannel());
        assertEquals(PayTradeType.NATIVE, response.getTradeType());
        assertEquals("NATIVE_20261023_001", response.getOutTradeNo());
        assertEquals(new BigDecimal("99.90"), response.getAmount());
        assertEquals("weixin://wxpay/bizpayurl?pr=mock_native_qr_code", response.getQrCode());
        assertEquals(PayTradeState.NOTPAY, response.getTradeState());

        RecordedRequest recorded = server.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertEquals("/v3/pay/transactions/native", recorded.getPath());
        assertTrue(recorded.getHeader("Authorization").startsWith("WECHATPAY2-SHA256-RSA2048"));
        String requestBody = recorded.getBody().readUtf8();
        assertTrue(requestBody.contains("\"appid\":\"wx_default_app\""));
        assertTrue(requestBody.contains("\"mchid\":\"1900000001\""));
        assertTrue(requestBody.contains("\"total\":9990"));
    }

    // =========================================================================
    // 2. JSAPI 小程序/公众号支付与全自动二次加签测试
    // =========================================================================

    @Test
    @DisplayName("payJsapi: 服务端全自动二次加签与 5 元素封包")
    void testPayJsapiAutoSecondarySigning() throws Exception {
        String mockPrepayJson = "{\"prepay_id\":\"wx20261023_jsapi_prepay_id_001\"}";
        server.enqueue(mockResponse(200, mockPrepayJson));

        WechatPayRequest request = WechatPayRequest.builder()
                .outTradeNo("JSAPI_20261023_002")
                .description("测试商品-JSAPI")
                .amount(new BigDecimal("10.50"))
                .openid("mock_user_openid_123")
                .build();

        PayResponse response = payTemplate.payJsapi(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals(PayChannel.WECHAT_PAY, response.getChannel());
        assertEquals(PayTradeType.JSAPI, response.getTradeType());
        assertEquals("wx20261023_jsapi_prepay_id_001", response.getPrepayId());

        // 验证 5 元素调起凭据包
        assertNotNull(response.getWechatJsapi());
        assertEquals("wx_default_app", response.getWechatJsapi().getAppId());
        assertNotNull(response.getWechatJsapi().getTimeStamp());
        assertNotNull(response.getWechatJsapi().getNonceStr());
        assertEquals("prepay_id=wx20261023_jsapi_prepay_id_001", response.getWechatJsapi().getPackageVal());
        assertEquals("prepay_id=wx20261023_jsapi_prepay_id_001", response.getWechatJsapi().getPackage());
        assertEquals("RSA", response.getWechatJsapi().getSignType());
        assertNotNull(response.getWechatJsapi().getPaySign());

        // 验证服务端计算的二次加签是否可被平台公钥验签通过
        String signMessage = response.getWechatJsapi().getAppId() + "\n"
                + response.getWechatJsapi().getTimeStamp() + "\n"
                + response.getWechatJsapi().getNonceStr() + "\n"
                + response.getWechatJsapi().getPackageVal() + "\n";
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(testCert.getPublicKey());
        verifier.update(signMessage.getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getDecoder().decode(response.getWechatJsapi().getPaySign())));
    }

    // =========================================================================
    // 3. H5 手机网页跳转支付测试
    // =========================================================================

    @Test
    @DisplayName("payH5: H5 手机外部浏览器下单与 payUrl 获取")
    void testPayH5() throws Exception {
        String mockH5Json = "{\"h5_url\":\"https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?prepay_id=wx2026_h5_001\"}";
        server.enqueue(mockResponse(200, mockH5Json));

        WechatPayRequest request = WechatPayRequest.builder()
                .outTradeNo("H5_20261023_003")
                .description("测试商品-H5")
                .amount(new BigDecimal("15.00"))
                .payerClientIp("192.168.1.100")
                .build();

        PayResponse response = payTemplate.payH5(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals(PayTradeType.H5, response.getTradeType());
        assertEquals("https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?prepay_id=wx2026_h5_001", response.getPayUrl());

        RecordedRequest recorded = server.takeRequest();
        assertEquals("/v3/pay/transactions/h5", recorded.getPath());
        String body = recorded.getBody().readUtf8();
        assertTrue(body.contains("\"payer_client_ip\":\"192.168.1.100\""));
    }

    // =========================================================================
    // 4. App 客户端加签测试
    // =========================================================================

    @Test
    @DisplayName("payApp: App 客户端下单与调起凭据自动加签")
    void testPayApp() throws Exception {
        String mockAppJson = "{\"prepay_id\":\"wx20261023_app_prepay_id_001\"}";
        server.enqueue(mockResponse(200, mockAppJson));

        WechatPayRequest request = WechatPayRequest.builder()
                .outTradeNo("APP_20261023_004")
                .description("测试商品-APP")
                .amount(new BigDecimal("199.00"))
                .build();

        PayResponse response = payTemplate.payApp(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals(PayTradeType.APP, response.getTradeType());
        assertEquals("wx20261023_app_prepay_id_001", response.getPrepayId());
        assertNotNull(response.getWechatApp());
        assertEquals("wx_default_app", response.getWechatApp().getAppid());
        assertEquals("1900000001", response.getWechatApp().getPartnerid());
        assertEquals("wx20261023_app_prepay_id_001", response.getWechatApp().getPrepayid());
        assertEquals("Sign=WXPay", response.getWechatApp().getPackageVal());
        assertNotNull(response.getWechatApp().getSign());
    }

    // =========================================================================
    // 5. 条码付 (Barcode / MicroPay) 测试
    // =========================================================================

    @Test
    @DisplayName("payBarcode: 付款码被扫支付同步成功")
    void testPayBarcode() throws Exception {
        String mockBarcodeResp = """
                {
                  "mchid": "1900000001",
                  "appid": "wx_default_app",
                  "out_trade_no": "BARCODE_20261023_005",
                  "transaction_id": "420000202610230000000001",
                  "trade_type": "MICROPAY",
                  "trade_state": "SUCCESS",
                  "trade_state_desc": "支付成功",
                  "bank_type": "CMC",
                  "amount": {
                    "total": 5000,
                    "currency": "CNY"
                  }
                }
                """;
        server.enqueue(mockResponse(200, mockBarcodeResp));

        WechatPayRequest request = WechatPayRequest.builder()
                .outTradeNo("BARCODE_20261023_005")
                .description("收银台扫码")
                .amount(new BigDecimal("50.00"))
                .authCode("134567890123456789")
                .build();

        PayResponse response = payTemplate.payBarcode(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals(PayTradeType.BARCODE, response.getTradeType());
        assertEquals(PayTradeState.SUCCESS, response.getTradeState());
        assertEquals("420000202610230000000001", response.getTransactionId());
        assertEquals(new BigDecimal("50.00"), response.getAmount());

        RecordedRequest recorded = server.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertEquals("/v3/pay/transactions/codepay", recorded.getPath());
        String body = recorded.getBody().readUtf8();
        assertTrue(body.contains("\"auth_code\":\"134567890123456789\""));
        assertTrue(body.contains("\"total\":5000"));
    }

    // =========================================================================
    // 6. 订单查询与关单测试 (含单标识重载)
    // =========================================================================

    @Test
    @DisplayName("queryOrder: 查询订单并精准映射 USERPAYING 状态")
    void testQueryOrder() throws Exception {
        String mockQueryResp = """
                {
                  "mchid": "1900000001",
                  "appid": "wx_default_app",
                  "out_trade_no": "QUERY_20261023_006",
                  "transaction_id": "420000202610230000000002",
                  "trade_type": "NATIVE",
                  "trade_state": "USERPAYING",
                  "trade_state_desc": "用户正在支付中",
                  "amount": {
                    "total": 3000,
                    "currency": "CNY"
                  }
                }
                """;
        server.enqueue(mockResponse(200, mockQueryResp));

        PayResponse response = payTemplate.queryOrder("QUERY_20261023_006");

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals(PayTradeState.USERPAYING, response.getTradeState());
        assertEquals("USERPAYING", response.getRawTradeState());
        assertEquals("420000202610230000000002", response.getTransactionId());
        assertEquals(new BigDecimal("30.00"), response.getAmount());

        RecordedRequest recorded = server.takeRequest();
        assertEquals("GET", recorded.getMethod());
        assertEquals("/v3/pay/transactions/out-trade-no/QUERY_20261023_006?mchid=1900000001", recorded.getPath());
    }

    @Test
    @DisplayName("closeOrder: 成功关单与单标识重载方法")
    void testCloseOrder() throws Exception {
        // 关单微信标准响应为 HTTP 204 No Content
        server.enqueue(mockResponse(204, null));

        PayResponse response = payTemplate.closeOrder("CLOSE_20261023_007");

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals(PayTradeState.CLOSED, response.getTradeState());
        assertEquals("CLOSE_20261023_007", response.getOutTradeNo());

        RecordedRequest recorded = server.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertEquals("/v3/pay/transactions/out-trade-no/CLOSE_20261023_007/close", recorded.getPath());
        assertTrue(recorded.getBody().readUtf8().contains("\"mchid\":\"1900000001\""));
    }

    // =========================================================================
    // 7. 服务商模式测试 (ISV特约商户)
    // =========================================================================

    @Test
    @DisplayName("服务商模式 (Partner): Native 下单自动注入 sp_mchid 与 sub_mchid")
    void testPartnerModeNative() throws Exception {
        String mockResponseJson = "{\"code_url\":\"weixin://wxpay/bizpayurl?pr=mock_partner_code_url\"}";
        server.enqueue(mockResponse(200, mockResponseJson));

        WechatPayRequest request = WechatPayRequest.builder()
                .merchantKey("partner_tenant")
                .outTradeNo("PARTNER_20261023_008")
                .description("服务商多商户商品")
                .amount(new BigDecimal("88.88"))
                .build();

        PayResponse response = payTemplate.payNative(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals("weixin://wxpay/bizpayurl?pr=mock_partner_code_url", response.getQrCode());

        RecordedRequest recorded = server.takeRequest();
        assertEquals("/v3/pay/partner/transactions/native", recorded.getPath());
        String body = recorded.getBody().readUtf8();
        assertTrue(body.contains("\"sp_appid\":\"wx_sp_app\""));
        assertTrue(body.contains("\"sp_mchid\":\"1900000000\""));
        assertTrue(body.contains("\"sub_mchid\":\"1900000002\""));
    }

    // =========================================================================
    // 8. 异常分支与因果链转译测试
    // =========================================================================

    @Test
    @DisplayName("异常防卫: 校验入参非法金额与缺失单号 Fail-Fast 拦截")
    void testValidationFailFast() {
        // 1. 金额小于等于 0
        WechatPayRequest invalidAmountReq = WechatPayRequest.builder()
                .outTradeNo("FAIL_001")
                .description("非法金额")
                .amount(new BigDecimal("-1.00"))
                .build();
        PayException ex1 = assertThrows(PayException.class, () -> payTemplate.payNative(invalidAmountReq));
        assertEquals(PayResultCode.PAY_AMOUNT_INVALID, ex1.getResultCode());

        // 2. 缺失订单号
        WechatPayRequest noOrderReq = WechatPayRequest.builder()
                .description("缺失订单号")
                .amount(new BigDecimal("1.00"))
                .build();
        PayException ex2 = assertThrows(PayException.class, () -> payTemplate.payNative(noOrderReq));
        assertEquals(PayResultCode.PAY_PARAM_INVALID, ex2.getResultCode());
    }

    @Test
    @DisplayName("异常转译: 平台应答验签失败转译为 PaySignException")
    void testSignatureValidationFailure() {
        // 构造一个故意破坏签名的 Mock 响应
        String badBase64Signature = Base64.getEncoder().encodeToString("invalid_signature_bytes".getBytes(StandardCharsets.UTF_8));
        MockResponse badSignResponse = new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setHeader("Wechatpay-Timestamp", String.valueOf(Instant.now().getEpochSecond()))
                .setHeader("Wechatpay-Nonce", "bad_nonce")
                .setHeader("Wechatpay-Signature", badBase64Signature)
                .setHeader("Wechatpay-Serial", testCertSerial)
                .setBody("{\"code_url\":\"weixin://broken\"}");
        server.enqueue(badSignResponse);

        WechatPayRequest request = WechatPayRequest.builder()
                .outTradeNo("BAD_SIGN_001")
                .description("验签失败测试")
                .amount(new BigDecimal("10.00"))
                .build();

        PaySignException ex = assertThrows(PaySignException.class, () -> payTemplate.payNative(request));
        assertEquals(PayResultCode.PAY_SIGN_VERIFY_ERROR, ex.getResultCode());
    }

    @Test
    @DisplayName("异常转译: 微信返回 ORDER_NOT_EXIST 错误转译为 PAY_ORDER_NOT_FOUND")
    void testOrderNotExistTranslation() throws Exception {
        String errorJson = "{\"code\":\"ORDER_NOT_EXIST\",\"message\":\"此交易订单号不存在\"}";
        server.enqueue(mockResponse(404, errorJson));

        PayTradeException ex = assertThrows(PayTradeException.class, () -> payTemplate.queryOrder("NON_EXIST_ORDER"));
        assertEquals(PayResultCode.PAY_ORDER_NOT_FOUND, ex.getResultCode());
        assertEquals("ORDER_NOT_EXIST", ex.getRawErrorCode());
        assertEquals(404, ex.getHttpStatus());
    }

    @Test
    @DisplayName("官方客户端逃生舱：获取底层 HttpClient 与 getClient 别名")
    void testClientEscapeHatch() {
        assertNotNull(payTemplate.getHttpClient());
        assertNotNull(payTemplate.getClient());
        assertEquals(payTemplate.getHttpClient(), payTemplate.getClient());
        assertNotNull(payTemplate.getRefundService());
    }

    // =========================================================================
    // 退款业务测试 (refund & queryRefund)
    // =========================================================================

    @Test
    @DisplayName("refund: 直连商户部分退款与出站报文校验")
    void testRefundDirectSuccess() throws Exception {
        String mockRefundJson = """
                {
                  "refund_id": "50000000382019052709732678859",
                  "out_refund_no": "R_20261024_001",
                  "transaction_id": "1217752501201407033233368018",
                  "out_trade_no": "T_20261024_001",
                  "channel": "ORIGINAL",
                  "user_received_account": "支付用户零钱",
                  "success_time": "2026-10-24T12:00:00+08:00",
                  "create_time": "2026-10-24T11:59:00+08:00",
                  "status": "SUCCESS",
                  "amount": {
                    "total": 10000,
                    "refund": 2550,
                    "payer_total": 10000,
                    "payer_refund": 2550,
                    "currency": "CNY"
                  }
                }
                """;
        server.enqueue(mockResponse(200, mockRefundJson));

        WechatRefundRequest request = WechatRefundRequest.builder()
                .outTradeNo("T_20261024_001")
                .outRefundNo("R_20261024_001")
                .totalAmount(new BigDecimal("100.00"))
                .refundAmount(new BigDecimal("25.50"))
                .reason("用户申请部分退款")
                .build();

        PayRefundResponse response = payTemplate.refund(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals(PayChannel.WECHAT_PAY, response.getChannel());
        assertEquals("T_20261024_001", response.getOutTradeNo());
        assertEquals("R_20261024_001", response.getOutRefundNo());
        assertEquals("50000000382019052709732678859", response.getRefundId());
        assertEquals("1217752501201407033233368018", response.getTransactionId());
        assertEquals(new BigDecimal("100.00"), response.getTotalAmount());
        assertEquals(new BigDecimal("25.50"), response.getRefundAmount());
        assertEquals(PayRefundState.SUCCESS, response.getRefundState());
        assertEquals("支付用户零钱", response.getUserReceivedAccount());

        RecordedRequest recordedReq = server.takeRequest();
        assertEquals("POST", recordedReq.getMethod());
        assertEquals("/v3/refund/domestic/refunds", recordedReq.getPath());
        String body = recordedReq.getBody().readUtf8();
        assertTrue(body.contains("\"out_refund_no\":\"R_20261024_001\""));
        assertTrue(body.contains("\"out_trade_no\":\"T_20261024_001\""));
        assertTrue(body.contains("\"refund\":2550"));
        assertTrue(body.contains("\"total\":10000"));
    }

    @Test
    @DisplayName("refund: 服务商模式特约子商户退款 (sub_mchid)")
    void testRefundPartnerMode() throws Exception {
        String mockRefundJson = """
                {
                  "refund_id": "50000000382019052709732678860",
                  "out_refund_no": "R_SP_001",
                  "out_trade_no": "T_SP_001",
                  "channel": "ORIGINAL",
                  "status": "PROCESSING",
                  "amount": {
                    "total": 5000,
                    "refund": 5000,
                    "currency": "CNY"
                  }
                }
                """;
        server.enqueue(mockResponse(200, mockRefundJson));

        WechatRefundRequest request = WechatRefundRequest.builder()
                .merchantKey("partner_tenant")
                .subMchId("1900000002")
                .outTradeNo("T_SP_001")
                .outRefundNo("R_SP_001")
                .totalAmount(new BigDecimal("50.00"))
                .refundAmount(new BigDecimal("50.00"))
                .build();

        PayRefundResponse response = payTemplate.refund(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals(PayRefundState.PROCESSING, response.getRefundState());

        RecordedRequest recordedReq = server.takeRequest();
        String body = recordedReq.getBody().readUtf8();
        assertTrue(body.contains("\"sub_mchid\":\"1900000002\""));
    }

    @Test
    @DisplayName("queryRefund: 根据商户退款单号查询退款状态")
    void testQueryRefundSuccess() throws Exception {
        String mockRefundJson = """
                {
                  "refund_id": "50000000382019052709732678861",
                  "out_refund_no": "R_QUERY_001",
                  "out_trade_no": "T_QUERY_001",
                  "status": "SUCCESS",
                  "amount": {
                    "total": 8800,
                    "refund": 8800,
                    "currency": "CNY"
                  }
                }
                """;
        server.enqueue(mockResponse(200, mockRefundJson));

        PayRefundResponse response = payTemplate.queryRefund("R_QUERY_001");

        assertNotNull(response);
        assertEquals(PayRefundState.SUCCESS, response.getRefundState());
        assertEquals(new BigDecimal("88.00"), response.getRefundAmount());

        RecordedRequest recordedReq = server.takeRequest();
        assertEquals("GET", recordedReq.getMethod());
        assertTrue(recordedReq.getPath().contains("/v3/refund/domestic/refunds/R_QUERY_001"));
    }

    @Test
    @DisplayName("refund: 校验防御 - 退款金额大于原订单金额或金额非正")
    void testRefundValidationErrors() {
        // 退款金额大于原订单金额
        WechatRefundRequest overAmountReq = WechatRefundRequest.builder()
                .outTradeNo("T_ERR_001")
                .outRefundNo("R_ERR_001")
                .totalAmount(new BigDecimal("10.00"))
                .refundAmount(new BigDecimal("10.01"))
                .build();
        PayException ex1 = assertThrows(PayException.class, () -> payTemplate.refund(overAmountReq));
        assertEquals(PayResultCode.PAY_PARAM_INVALID, ex1.getResultCode());

        // 缺少退款单号
        WechatRefundRequest noRefundNoReq = WechatRefundRequest.builder()
                .outTradeNo("T_ERR_002")
                .totalAmount(new BigDecimal("10.00"))
                .refundAmount(new BigDecimal("5.00"))
                .build();
        PayException ex2 = assertThrows(PayException.class, () -> payTemplate.refund(noRefundNoReq));
        assertEquals(PayResultCode.PAY_PARAM_INVALID, ex2.getResultCode());
    }

    // =========================================================================
    // 10. 资金分账单元测试 (Profit Sharing)
    // =========================================================================

    @Test
    @DisplayName("payNative: 前置指定分账标记 (profitSharing=true) 时组装 settle_info")
    void testPayNativeWithProfitSharing() throws Exception {
        String mockResponseJson = "{\"code_url\":\"weixin://wxpay/bizpayurl?pr=mock_ps_native_qr_code\"}";
        server.enqueue(mockResponse(200, mockResponseJson));

        WechatPayRequest request = WechatPayRequest.builder()
                .outTradeNo("NATIVE_PS_001")
                .description("测试分账商品")
                .amount(new BigDecimal("100.00"))
                .profitSharing(true)
                .build();

        PayResponse response = payTemplate.payNative(request);
        assertNotNull(response);
        assertTrue(response.isSuccess());

        RecordedRequest recordedReq = server.takeRequest();
        String requestBody = recordedReq.getBody().readUtf8();
        assertTrue(requestBody.contains("\"settle_info\""), "出站请求报文必须包含 settle_info");
        assertTrue(requestBody.contains("\"profit_sharing\":true"), "出站请求报文必须包含 profit_sharing=true");
    }

    @Test
    @DisplayName("profitSharing: 物理开关默认关闭时调用任何分账方法均快速失败拦截")
    void testProfitSharingDisabledInterception() {
        assertFalse(payTemplate.isProfitSharingEnabled(), "默认情况下资金分账总开关必须为关闭状态");

        // 1. 添加接收方
        AddReceiverRequest addReq = new AddReceiverRequest();
        PayException ex1 = assertThrows(PayException.class, () -> payTemplate.addProfitSharingReceiver(addReq));
        assertEquals(PayResultCode.PAY_FEATURE_DISABLED, ex1.getResultCode());

        // 2. 删除接收方
        DeleteReceiverRequest delReq = new DeleteReceiverRequest();
        PayException ex2 = assertThrows(PayException.class, () -> payTemplate.deleteProfitSharingReceiver(delReq));
        assertEquals(PayResultCode.PAY_FEATURE_DISABLED, ex2.getResultCode());

        // 3. 请求分账
        CreateOrderRequest createReq = new CreateOrderRequest();
        PayException ex3 = assertThrows(PayException.class, () -> payTemplate.createProfitSharing(createReq));
        assertEquals(PayResultCode.PAY_FEATURE_DISABLED, ex3.getResultCode());

        // 4. 查询分账
        PayException ex4 = assertThrows(PayException.class, () -> payTemplate.queryProfitSharing("PS_001", "TX_001"));
        assertEquals(PayResultCode.PAY_FEATURE_DISABLED, ex4.getResultCode());

        // 5. 完结解冻资金
        PayException ex5 = assertThrows(PayException.class, () -> payTemplate.unfreezeProfitSharing("PS_001", "TX_001", "解冻"));
        assertEquals(PayResultCode.PAY_FEATURE_DISABLED, ex5.getResultCode());

        // 6. 分账回退
        CreateReturnOrderRequest returnReq = new CreateReturnOrderRequest();
        PayException ex6 = assertThrows(PayException.class, () -> payTemplate.returnProfitSharing(returnReq));
        assertEquals(PayResultCode.PAY_FEATURE_DISABLED, ex6.getResultCode());

        // 7. 查询分账回退
        PayException ex7 = assertThrows(PayException.class, () -> payTemplate.queryProfitSharingReturn("RET_001", "PS_001"));
        assertEquals(PayResultCode.PAY_FEATURE_DISABLED, ex7.getResultCode());

        // 8. 逃生舱获取
        PayException ex8 = assertThrows(PayException.class, () -> payTemplate.getProfitsharingService());
        assertEquals(PayResultCode.PAY_FEATURE_DISABLED, ex8.getResultCode());
    }

    @Test
    @DisplayName("profitSharing: 全生命周期成功流转（接收方、分账、查询、解冻、回退）")
    void testProfitSharingLifecycleSuccess() throws Exception {
        payTemplate.setProfitSharingEnabled(true);
        assertTrue(payTemplate.isProfitSharingEnabled());

        // 1. 添加接收方
        server.enqueue(mockResponse(200, "{\"type\":\"MERCHANT_ID\",\"account\":\"1900000002\",\"relation_type\":\"USER\"}"));
        AddReceiverRequest addReq = new AddReceiverRequest();
        addReq.setType(ReceiverType.MERCHANT_ID);
        addReq.setAccount("1900000002");
        addReq.setRelationType(ReceiverRelationType.USER);

        AddReceiverResponse addResp = payTemplate.addProfitSharingReceiver(addReq);
        assertNotNull(addResp);
        assertEquals("1900000002", addResp.getAccount());

        RecordedRequest recordedAdd = server.takeRequest();
        assertEquals("POST", recordedAdd.getMethod());
        assertTrue(recordedAdd.getPath().contains("/v3/profitsharing/receivers/add"));

        // 2. 删除接收方
        server.enqueue(mockResponse(200, "{\"type\":\"MERCHANT_ID\",\"account\":\"1900000002\"}"));
        DeleteReceiverRequest delReq = new DeleteReceiverRequest();
        delReq.setType(ReceiverType.MERCHANT_ID);
        delReq.setAccount("1900000002");

        DeleteReceiverResponse delResp = payTemplate.deleteProfitSharingReceiver(delReq);
        assertNotNull(delResp);
        assertEquals("1900000002", delResp.getAccount());

        RecordedRequest recordedDel = server.takeRequest();
        assertEquals("POST", recordedDel.getMethod());
        assertTrue(recordedDel.getPath().contains("/v3/profitsharing/receivers/delete"));

        // 3. 请求分账
        server.enqueue(mockResponse(200, "{\"transaction_id\":\"4200002000202610240001\",\"out_order_no\":\"PS_20261024_001\",\"order_id\":\"3000002000202610240001\",\"state\":\"FINISHED\"}"));
        CreateOrderRequest createReq = new CreateOrderRequest();
        createReq.setTransactionId("4200002000202610240001");
        createReq.setOutOrderNo("PS_20261024_001");
        CreateOrderReceiver receiver = new CreateOrderReceiver();
        receiver.setType("MERCHANT_ID");
        receiver.setAccount("1900000002");
        receiver.setAmount(100L);
        receiver.setDescription("分账测试");
        createReq.setReceivers(java.util.List.of(receiver));

        OrdersEntity createResp = payTemplate.createProfitSharing(createReq);
        assertNotNull(createResp);
        assertEquals("PS_20261024_001", createResp.getOutOrderNo());
        assertEquals("3000002000202610240001", createResp.getOrderId());

        RecordedRequest recordedCreate = server.takeRequest();
        assertEquals("POST", recordedCreate.getMethod());
        assertTrue(recordedCreate.getPath().contains("/v3/profitsharing/orders"));

        // 4. 查询分账结果
        server.enqueue(mockResponse(200, "{\"transaction_id\":\"4200002000202610240001\",\"out_order_no\":\"PS_20261024_001\",\"order_id\":\"3000002000202610240001\",\"state\":\"FINISHED\"}"));
        OrdersEntity queryResp = payTemplate.queryProfitSharing("PS_20261024_001", "4200002000202610240001");
        assertNotNull(queryResp);
        assertEquals("PS_20261024_001", queryResp.getOutOrderNo());

        RecordedRequest recordedQuery = server.takeRequest();
        assertEquals("GET", recordedQuery.getMethod());
        assertTrue(recordedQuery.getPath().contains("/v3/profitsharing/orders/PS_20261024_001"));
        assertTrue(recordedQuery.getPath().contains("transaction_id=4200002000202610240001"));

        // 5. 完结解冻在途剩余资金
        server.enqueue(mockResponse(200, "{\"transaction_id\":\"4200002000202610240001\",\"out_order_no\":\"UNFREEZE_001\",\"order_id\":\"3000002000202610240001\",\"state\":\"FINISHED\"}"));
        OrdersEntity unfreezeResp = payTemplate.unfreezeProfitSharing("UNFREEZE_001", "4200002000202610240001", "分账完成解冻剩余资金");
        assertNotNull(unfreezeResp);
        assertEquals("UNFREEZE_001", unfreezeResp.getOutOrderNo());

        RecordedRequest recordedUnfreeze = server.takeRequest();
        assertEquals("POST", recordedUnfreeze.getMethod());
        assertTrue(recordedUnfreeze.getPath().contains("/v3/profitsharing/orders/unfreeze"));
        String unfreezeBody = recordedUnfreeze.getBody().readUtf8();
        assertTrue(unfreezeBody.contains("\"out_order_no\":\"UNFREEZE_001\""));
        assertTrue(unfreezeBody.contains("\"description\":\"分账完成解冻剩余资金\""));

        // 6. 请求分账回退
        server.enqueue(mockResponse(200, "{\"order_id\":\"3000002000202610240001\",\"out_order_no\":\"PS_20261024_001\",\"out_return_no\":\"RET_001\",\"return_mchid\":\"1900000002\",\"amount\":100,\"result\":\"SUCCESS\"}"));
        CreateReturnOrderRequest returnReq = new CreateReturnOrderRequest();
        returnReq.setOutOrderNo("PS_20261024_001");
        returnReq.setOutReturnNo("RET_001");
        returnReq.setReturnMchid("1900000002");
        returnReq.setAmount(100L);
        returnReq.setDescription("用户退货分账回退");

        ReturnOrdersEntity returnResp = payTemplate.returnProfitSharing(returnReq);
        assertNotNull(returnResp);
        assertEquals("RET_001", returnResp.getOutReturnNo());

        RecordedRequest recordedReturn = server.takeRequest();
        assertEquals("POST", recordedReturn.getMethod());
        assertTrue(recordedReturn.getPath().contains("/v3/profitsharing/return-orders"));

        // 7. 查询分账回退
        server.enqueue(mockResponse(200, "{\"order_id\":\"3000002000202610240001\",\"out_order_no\":\"PS_20261024_001\",\"out_return_no\":\"RET_001\",\"return_mchid\":\"1900000002\",\"amount\":100,\"result\":\"SUCCESS\"}"));
        ReturnOrdersEntity queryReturnResp = payTemplate.queryProfitSharingReturn("RET_001", "PS_20261024_001");
        assertNotNull(queryReturnResp);
        assertEquals("RET_001", queryReturnResp.getOutReturnNo());

        RecordedRequest recordedQueryReturn = server.takeRequest();
        assertEquals("GET", recordedQueryReturn.getMethod());
        assertTrue(recordedQueryReturn.getPath().contains("/v3/profitsharing/return-orders/RET_001"));
        assertTrue(recordedQueryReturn.getPath().contains("out_order_no=PS_20261024_001"));

        // 8. 查询剩余待分金额
        server.enqueue(mockResponse(200, "{\"transaction_id\":\"4200002000202610240001\",\"unsplit_amount\":500}"));
        QueryOrderAmountRequest amountReq = new QueryOrderAmountRequest();
        amountReq.setTransactionId("4200002000202610240001");
        var amountResp = payTemplate.queryProfitSharingAmount(amountReq);
        assertNotNull(amountResp);
        assertEquals(500L, amountResp.getUnsplitAmount());

        RecordedRequest recordedAmount = server.takeRequest();
        assertEquals("GET", recordedAmount.getMethod());
        assertTrue(recordedAmount.getPath().contains("/v3/profitsharing/transactions/4200002000202610240001/amounts"));

        // 9. 查询最大分账比例
        server.enqueue(mockResponse(200, "{\"sub_mchid\":\"1900000002\",\"max_ratio\":3000}"));
        QueryMerchantRatioRequest ratioReq = new QueryMerchantRatioRequest();
        ratioReq.setSubMchid("1900000002");
        var ratioResp = payTemplate.queryProfitSharingMerchantRatio(ratioReq);
        assertNotNull(ratioResp);
        assertEquals(3000, ratioResp.getMaxRatio());

        RecordedRequest recordedRatio = server.takeRequest();
        assertEquals("GET", recordedRatio.getMethod());
        assertTrue(recordedRatio.getPath().contains("/v3/profitsharing/merchant-configs/1900000002"));

        // 10. 逃生舱实例验证
        assertNotNull(payTemplate.getProfitsharingService());
    }

    @Test
    @DisplayName("profitSharing: 参数非法防卫校验测试")
    void testProfitSharingValidationErrors() {
        payTemplate.setProfitSharingEnabled(true);

        // 1. null request
        PayException exNull = assertThrows(PayException.class, () -> payTemplate.createProfitSharing(null));
        assertEquals(PayResultCode.PAY_PARAM_INVALID, exNull.getResultCode());

        // 2. createOrder 缺少 outOrderNo
        CreateOrderRequest noOutOrderReq = new CreateOrderRequest();
        noOutOrderReq.setTransactionId("TX_001");
        noOutOrderReq.setReceivers(java.util.List.of(new CreateOrderReceiver()));
        PayException exNoOutOrder = assertThrows(PayException.class, () -> payTemplate.createProfitSharing(noOutOrderReq));
        assertEquals(PayResultCode.PAY_PARAM_INVALID, exNoOutOrder.getResultCode());

        // 3. createOrder 缺少 receivers
        CreateOrderRequest noReceiversReq = new CreateOrderRequest();
        noReceiversReq.setOutOrderNo("PS_001");
        noReceiversReq.setTransactionId("TX_001");
        PayException exNoReceivers = assertThrows(PayException.class, () -> payTemplate.createProfitSharing(noReceiversReq));
        assertEquals(PayResultCode.PAY_PARAM_INVALID, exNoReceivers.getResultCode());

        // 4. unfreezeOrder 缺少 description
        PayException exNoDesc = assertThrows(PayException.class, () -> payTemplate.unfreezeProfitSharing("PS_001", "TX_001", ""));
        assertEquals(PayResultCode.PAY_PARAM_INVALID, exNoDesc.getResultCode());

        // 5. returnOrder 缺少 orderId 与 outOrderNo
        CreateReturnOrderRequest badReturnReq = new CreateReturnOrderRequest();
        badReturnReq.setOutReturnNo("RET_001");
        badReturnReq.setReturnMchid("1900000002");
        badReturnReq.setAmount(100L);
        badReturnReq.setDescription("回退");
        PayException exBadReturn = assertThrows(PayException.class, () -> payTemplate.returnProfitSharing(badReturnReq));
        assertEquals(PayResultCode.PAY_PARAM_INVALID, exBadReturn.getResultCode());
    }

    // =========================================================================
    // 辅助工具方法
    // =========================================================================

    /**
     * 构造携带微信支付标准验签标头的 MockResponse
     */
    private MockResponse mockResponse(int statusCode, String body) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        String nonce = "mock_nonce_" + System.currentTimeMillis();
        String signPayload = timestamp + "\n" + nonce + "\n" + (body != null ? body : "") + "\n";

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(testPrivateKey);
        signer.update(signPayload.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(signer.sign());

        MockResponse response = new MockResponse()
                .setResponseCode(statusCode)
                .setHeader("Content-Type", "application/json")
                .setHeader("Wechatpay-Timestamp", String.valueOf(timestamp))
                .setHeader("Wechatpay-Nonce", nonce)
                .setHeader("Wechatpay-Signature", signature)
                .setHeader("Wechatpay-Serial", testCertSerial);

        if (body != null) {
            response.setBody(body);
        }
        return response;
    }
}
