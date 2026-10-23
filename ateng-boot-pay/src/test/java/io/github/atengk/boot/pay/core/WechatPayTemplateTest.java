package io.github.atengk.boot.pay.core;

import com.wechat.pay.java.core.http.DefaultHttpClientBuilder;
import com.wechat.pay.java.core.http.HttpClient;
import io.github.atengk.boot.pay.config.properties.PayProperties;
import io.github.atengk.boot.pay.config.properties.WechatMerchantProperties;
import io.github.atengk.boot.pay.config.properties.WechatPayProperties;
import io.github.atengk.boot.pay.core.certificate.WechatAutoCertificateManager;
import io.github.atengk.boot.pay.core.merchant.NamedMerchantRegistry;
import io.github.atengk.boot.pay.enums.PayChannel;
import io.github.atengk.boot.pay.enums.PayResultCode;
import io.github.atengk.boot.pay.enums.PayTradeState;
import io.github.atengk.boot.pay.enums.PayTradeType;
import io.github.atengk.boot.pay.exception.PayException;
import io.github.atengk.boot.pay.exception.PaySignException;
import io.github.atengk.boot.pay.exception.PayTradeException;
import io.github.atengk.boot.pay.model.request.WechatPayRequest;
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
