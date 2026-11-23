package io.github.atengk.boot.pay.core;

import com.wechat.pay.java.core.http.DefaultHttpClientBuilder;
import com.wechat.pay.java.core.http.HttpClient;
import com.wechat.pay.java.service.billdownload.model.BillType;
import com.wechat.pay.java.service.billdownload.model.GetFundFlowBillRequest;
import com.wechat.pay.java.service.billdownload.model.GetTradeBillRequest;
import com.wechat.pay.java.service.billdownload.model.QueryBillEntity;
import com.wechat.pay.java.service.billdownload.model.TarType;
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
import io.github.atengk.boot.pay.enums.PayTransferState;
import io.github.atengk.boot.pay.exception.PayException;
import io.github.atengk.boot.pay.exception.PaySignException;
import io.github.atengk.boot.pay.exception.PayTradeException;
import io.github.atengk.boot.pay.model.request.UnifiedRefundRequest;
import io.github.atengk.boot.pay.model.request.WechatPayRequest;
import io.github.atengk.boot.pay.model.request.WechatRefundRequest;
import io.github.atengk.boot.pay.model.request.WechatTransferRequest;
import io.github.atengk.boot.pay.model.response.PayRefundResponse;
import io.github.atengk.boot.pay.model.response.PayResponse;
import io.github.atengk.boot.pay.model.response.PayTransferResponse;
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
        wechat.getReverseRetry().setDelay(Duration.ofMillis(20));
        wechat.getReverseRetry().setMaxDelay(Duration.ofMillis(20));

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

        // 静态平台公钥直连商户
        WechatMerchantProperties pubKeyMch = new WechatMerchantProperties();
        pubKeyMch.setMchId("1900000003");
        pubKeyMch.setAppId("wx_pubkey_app");
        pubKeyMch.setMerchantSerialNumber(testCertSerial);
        pubKeyMch.setPrivateKeyPath(CLASSPATH_KEY);
        pubKeyMch.setWechatPayPublicKeyPath("classpath:certs/test_public_key.pem");
        pubKeyMch.setWechatPayPublicKeyId("PUB_KEY_ID_TEST_001");
        pubKeyMch.setNotifyUrl("https://example.com/pay/callback/wechat");
        merchants.put("pubkey_mch", pubKeyMch);

        wechat.setMerchants(merchants);
        merchantRegistry = new NamedMerchantRegistry(payProperties);

        // 5. 初始化 WechatPayTemplate
        payTemplate = new WechatPayTemplate(merchantRegistry, certificateManager, payProperties);

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

    @Test
    @DisplayName("cancelOrder: 直连商户成功发起撤销冲正并断言 URL 与 mchid")
    void testCancelOrderDirect() throws Exception {
        server.enqueue(mockResponse(204, null));

        PayResponse response = payTemplate.cancelOrder("CANCEL_20261023_001");

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals(PayTradeState.REVOKED, response.getTradeState());
        assertEquals("CANCEL_20261023_001", response.getOutTradeNo());
        assertEquals("订单已成功撤销冲正", response.getTradeStateDesc());

        RecordedRequest recorded = server.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertEquals("/v3/pay/transactions/out-trade-no/CANCEL_20261023_001/reverse", recorded.getPath());
        assertTrue(recorded.getBody().readUtf8().contains("\"mchid\":\"1900000001\""));
    }

    @Test
    @DisplayName("cancelOrder: 服务商模式 (Partner) 撤销冲正注入 sp_mchid 与 sub_mchid")
    void testCancelOrderPartner() throws Exception {
        server.enqueue(mockResponse(204, null));

        WechatPayRequest request = WechatPayRequest.builder()
                .merchantKey("partner_tenant")
                .outTradeNo("CANCEL_PARTNER_002")
                .build();

        PayResponse response = payTemplate.cancelOrder(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals(PayTradeState.REVOKED, response.getTradeState());
        assertEquals("CANCEL_PARTNER_002", response.getOutTradeNo());

        RecordedRequest recorded = server.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertEquals("/v3/pay/partner/transactions/out-trade-no/CANCEL_PARTNER_002/reverse", recorded.getPath());
        String body = recorded.getBody().readUtf8();
        assertTrue(body.contains("\"sp_mchid\":\"1900000000\""));
        assertTrue(body.contains("\"sub_mchid\":\"1900000002\""));
    }

    @Test
    @DisplayName("cancelOrder: 遭遇微信服务端 SYSTEMERROR 异常时依托 RetryUtil 自动重试并最终成功")
    void testCancelOrderRetryResilience() throws Exception {
        String errorJson = "{\"code\":\"SYSTEMERROR\",\"message\":\"系统繁忙，请稍后重试\"}";
        server.enqueue(mockResponse(500, errorJson));
        server.enqueue(mockResponse(500, errorJson));
        server.enqueue(mockResponse(204, null));

        PayResponse response = payTemplate.cancelOrder("CANCEL_RETRY_003");

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals(PayTradeState.REVOKED, response.getTradeState());
        assertEquals(3, server.getRequestCount());
    }

    @Test
    @DisplayName("cancelOrder: 重试耗尽后如实转译业务异常并暴露真实因果链")
    void testCancelOrderRetryExhaustedFails() throws Exception {
        PayProperties customProps = new PayProperties();
        customProps.getWechat().getReverseRetry().setMaxAttempts(2);
        customProps.getWechat().getReverseRetry().setDelay(Duration.ofMillis(10));
        customProps.getWechat().getReverseRetry().setMaxDelay(Duration.ofMillis(10));
        WechatPayTemplate retryFailTemplate = new WechatPayTemplate(merchantRegistry, certificateManager, customProps);
        retryFailTemplate.setCustomHttpClient(payTemplate.getHttpClient());

        String errorJson = "{\"code\":\"SYSTEMERROR\",\"message\":\"系统繁忙，请稍后重试\"}";
        server.enqueue(mockResponse(500, errorJson));
        server.enqueue(mockResponse(500, errorJson));

        PayTradeException ex = assertThrows(PayTradeException.class, () -> retryFailTemplate.cancelOrder("CANCEL_FAIL_004"));
        assertEquals("SYSTEMERROR", ex.getRawErrorCode());
        assertTrue(ex.getRawErrorMessage().contains("系统繁忙"));
    }

    @Test
    @DisplayName("cancelOrder: 空入参或商户订单号缺失时 Fail-Fast 拦截")
    void testCancelOrderValidation() {
        assertThrows(PayException.class, () -> payTemplate.cancelOrder((WechatPayRequest) null));
        assertThrows(PayException.class, () -> payTemplate.cancelOrder((String) null));
        assertThrows(PayException.class, () -> payTemplate.cancelOrder("   "));
        assertThrows(PayException.class, () -> payTemplate.cancelOrder(WechatPayRequest.builder().build()));
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

        // queryRefund 空参数防卫
        PayException ex3 = assertThrows(PayException.class, () -> payTemplate.queryRefund((WechatRefundRequest) null));
        assertEquals(PayResultCode.PAY_PARAM_INVALID, ex3.getResultCode());

        // 非法资金账户 fundsAccount
        WechatRefundRequest badFundsReq = WechatRefundRequest.builder()
                .outTradeNo("T_ERR_003")
                .outRefundNo("R_ERR_003")
                .totalAmount(new BigDecimal("10.00"))
                .refundAmount(new BigDecimal("5.00"))
                .fundsAccount("INVALID_FUNDS_ACCOUNT")
                .build();
        PayException ex4 = assertThrows(PayException.class, () -> payTemplate.refund(badFundsReq));
        assertEquals(PayResultCode.PAY_PARAM_INVALID, ex4.getResultCode());
    }

    @Test
    @DisplayName("payNative: 超出 32 位整型分上限金额快速拦截防止静默截断")
    void testPayNativeAmountOverflowGuard() {
        WechatPayRequest overflowReq = WechatPayRequest.builder()
                .outTradeNo("OVERFLOW_20261118_001")
                .description("大额防溢出校验商品")
                .amount(new BigDecimal("21474836.48"))
                .build();

        PayException ex = assertThrows(PayException.class, () -> payTemplate.payNative(overflowReq));
        assertEquals(PayResultCode.PAY_AMOUNT_INVALID, ex.getResultCode());
        assertEquals(0, server.getRequestCount(), "金额溢出必须在调用网关前前置阻断");
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

    @Test
    @DisplayName("transfer: 物理总开关关闭时调用转账操作必须 Fail-Fast 抛出 PAY_FEATURE_DISABLED")
    void testTransferDisabledFailsFast() {
        payTemplate.setTransferEnabled(false);

        WechatTransferRequest request = WechatTransferRequest.builder()
                .outBatchNo("BATCH_20261024_001")
                .amount(new BigDecimal("10.00"))
                .openid("oUpF8uMuAJO_M2pxb1Q9zNjWeS6o")
                .build();

        PayException ex1 = assertThrows(PayException.class, () -> payTemplate.transfer(request));
        assertEquals(PayResultCode.PAY_FEATURE_DISABLED, ex1.getResultCode());

        PayException ex2 = assertThrows(PayException.class, () -> payTemplate.queryTransferDetail("BATCH_20261024_001", "DETAIL_001"));
        assertEquals(PayResultCode.PAY_FEATURE_DISABLED, ex2.getResultCode());

        PayException ex3 = assertThrows(PayException.class, () -> payTemplate.queryTransferBatch("BATCH_20261024_001"));
        assertEquals(PayResultCode.PAY_FEATURE_DISABLED, ex3.getResultCode());

        PayException ex4 = assertThrows(PayException.class, () -> payTemplate.getTransferBatchService());
        assertEquals(PayResultCode.PAY_FEATURE_DISABLED, ex4.getResultCode());

        PayException ex5 = assertThrows(PayException.class, () -> payTemplate.queryTransfer("BILL_DISABLED_001"));
        assertEquals(PayResultCode.PAY_FEATURE_DISABLED, ex5.getResultCode());

        PayException ex6 = assertThrows(PayException.class, () -> payTemplate.cancelTransfer("BILL_DISABLED_001"));
        assertEquals(PayResultCode.PAY_FEATURE_DISABLED, ex6.getResultCode());
    }

    @Test
    @DisplayName("transfer(legacyBatchMode=true): 显式开启旧版批次模式兼容调用 /v3/transfer/batches")
    void testInitiateSingleTransferAndQueryHermetic() throws Exception {
        payTemplate.setTransferEnabled(true);

        // 1. 模拟发起商家转账到零钱出站与应答
        server.enqueue(mockResponse(200, "{\"out_batch_no\":\"BATCH_TRANSFER_001\",\"batch_id\":\"103000007110109690030000001\",\"create_time\":\"2026-10-24T08:00:00+08:00\",\"batch_status\":\"ACCEPTED\"}"));

        WechatTransferRequest transferReq = WechatTransferRequest.builder()
                .outBatchNo("BATCH_TRANSFER_001")
                .amount(new BigDecimal("88.88"))
                .batchName("2026年终绩效奖金")
                .batchRemark("研发绩效结算")
                .transferRemark("研发绩效奖金")
                .openid("oUpF8uMuAJO_M2pxb1Q9zNjWeS6o")
                .userName("张三")
                .legacyBatchMode(true)
                .build();

        PayTransferResponse transferResp = payTemplate.transfer(transferReq);

        assertNotNull(transferResp);
        assertEquals(PayChannel.WECHAT_PAY, transferResp.getChannel());
        assertEquals("BATCH_TRANSFER_001", transferResp.getOutBizNo());
        assertEquals("103000007110109690030000001", transferResp.getOrderId());
        assertEquals(PayTransferState.PROCESSING, transferResp.getStatus());
        assertEquals(new BigDecimal("88.88"), transferResp.getAmount());
        assertEquals("oUpF8uMuAJO_M2pxb1Q9zNjWeS6o", transferResp.getPayeeAccount());
        assertEquals("张三", transferResp.getPayeeRealName());

        RecordedRequest recordedInitiate = server.takeRequest();
        assertEquals("POST", recordedInitiate.getMethod());
        assertTrue(recordedInitiate.getPath().contains("/v3/transfer/batches"));
        String initiateBody = recordedInitiate.getBody().readUtf8();
        assertTrue(initiateBody.contains("\"out_batch_no\":\"BATCH_TRANSFER_001\""));
        assertTrue(initiateBody.contains("\"total_amount\":8888"));
        assertTrue(initiateBody.contains("\"total_num\":1"));
        assertTrue(initiateBody.contains("\"openid\":\"oUpF8uMuAJO_M2pxb1Q9zNjWeS6o\""));

        // 2. 模拟根据商家批次单号与明细单号查询转账状态
        server.enqueue(mockResponse(200, "{\"mchid\":\"1900000001\",\"out_batch_no\":\"BATCH_TRANSFER_001\",\"batch_id\":\"103000007110109690030000001\",\"appid\":\"wx_default_app\",\"out_detail_no\":\"BATCH_TRANSFER_001\",\"detail_id\":\"104000007110109690030000001\",\"detail_status\":\"SUCCESS\",\"transfer_amount\":8888,\"transfer_remark\":\"研发绩效奖金\",\"openid\":\"oUpF8uMuAJO_M2pxb1Q9zNjWeS6o\",\"initiate_time\":\"2026-10-24T08:00:00+08:00\",\"update_time\":\"2026-10-24T08:05:00+08:00\"}"));

        PayTransferResponse detailResp = payTemplate.queryTransferDetail("BATCH_TRANSFER_001", "BATCH_TRANSFER_001");
        assertNotNull(detailResp);
        assertEquals(PayChannel.WECHAT_PAY, detailResp.getChannel());
        assertEquals("BATCH_TRANSFER_001", detailResp.getOutBizNo());
        assertEquals("103000007110109690030000001", detailResp.getOrderId());
        assertEquals("104000007110109690030000001", detailResp.getPayFundOrderId());
        assertEquals(PayTransferState.SUCCESS, detailResp.getStatus());
        assertEquals(new BigDecimal("88.88"), detailResp.getAmount());
        assertEquals("oUpF8uMuAJO_M2pxb1Q9zNjWeS6o", detailResp.getPayeeAccount());
        assertEquals("2026-10-24T08:05:00+08:00", detailResp.getPayTime());

        RecordedRequest recordedQuery = server.takeRequest();
        assertEquals("GET", recordedQuery.getMethod());
        assertTrue(recordedQuery.getPath().contains("/v3/transfer/batches/out-batch-no/BATCH_TRANSFER_001/details/out-detail-no/BATCH_TRANSFER_001"));

        // 3. 逃生舱实例检查
        assertNotNull(payTemplate.getTransferBatchService());
    }

    @Test
    @DisplayName("transfer: 默认新版商家转账全链路 (WAIT_USER_CONFIRM / packageInfo / user_name 加密 / 双路径查单 / cancelTransfer)")
    void testUserConfirmTransferMainFlowAndCancelHermetic() throws Exception {
        payTemplate.setTransferEnabled(true);

        // 1. 大额 >= 2000 元未传收款人真实姓名必须 Fail-Fast
        WechatTransferRequest largeWithoutName = WechatTransferRequest.builder()
                .outBatchNo("MCH_TRANS_LARGE_NO_NAME")
                .amount(new BigDecimal("2000.00"))
                .openid("oUpF8uMuAJO_M2pxb1Q9zNjWeS6o")
                .build();
        PayException largeEx = assertThrows(PayException.class, () -> payTemplate.transfer(largeWithoutName));
        assertEquals(PayResultCode.PAY_PARAM_INVALID, largeEx.getResultCode());

        // 1b. 场景报备项存在空白 infoType/infoContent 时必须 Fail-Fast
        WechatTransferRequest badReportReq = WechatTransferRequest.builder()
                .outBatchNo("MCH_TRANS_BAD_REPORT")
                .amount(new BigDecimal("10.00"))
                .openid("oUpF8uMuAJO_M2pxb1Q9zNjWeS6o")
                .transferSceneReportInfos(java.util.List.of(
                        io.github.atengk.boot.pay.model.request.TransferSceneReportInfo.builder()
                                .infoType("   ")
                                .infoContent("活动内容")
                                .build()))
                .build();
        PayException badReportEx = assertThrows(PayException.class, () -> payTemplate.transfer(badReportReq));
        assertEquals(PayResultCode.PAY_PARAM_INVALID, badReportEx.getResultCode());

        // 2. 新版商家转账发起（使用平台公钥商户 pubkey_mch + 场景报备 + 真实姓名加密）
        com.wechat.pay.java.core.Config pubKeyConfig = payTemplate.getWechatConfig("pubkey_mch");
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
        HttpClient pubKeyHttpClient = new DefaultHttpClientBuilder()
                .config(pubKeyConfig)
                .okHttpClient(redirectOkHttpClient)
                .build();

        String initiateJson = "{\"out_bill_no\":\"MCH_BILL_20261118_01\",\"transfer_bill_no\":\"133000007110109690030000001\",\"create_time\":\"2026-11-18T15:00:00+08:00\",\"state\":\"WAIT_USER_CONFIRM\",\"package_info\":\"MOCK_PKG_INFO_ABC\"}";
        server.enqueue(mockResponse(200, initiateJson, "PUB_KEY_ID_TEST_001"));

        var reportInfo = io.github.atengk.boot.pay.model.request.TransferSceneReportInfo.builder()
                .infoType("活动名称")
                .infoContent("会员拉新奖励")
                .build();
        WechatTransferRequest newReq = WechatTransferRequest.builder()
                .merchantKey("pubkey_mch")
                .outBatchNo("MCH_BILL_20261118_01")
                .transferSceneId("1000")
                .transferRemark("会员拉新现金红包")
                .amount(new BigDecimal("2000.00"))
                .openid("oUpF8uMuAJO_M2pxb1Q9zNjWeS6o")
                .userName("王五")
                .notifyUrl("https://example.com/transfer/notify")
                .transferSceneReportInfos(java.util.List.of(reportInfo))
                .build();

        assertFalse(newReq.toString().contains("王五"), "WechatTransferRequest.toString() 不得泄露收款人明文姓名");

        HttpClient defaultHttpClient = new DefaultHttpClientBuilder()
                .config(payTemplate.getWechatConfig())
                .okHttpClient(redirectOkHttpClient)
                .build();

        PayTransferResponse initResp;
        payTemplate.setCustomHttpClient(pubKeyHttpClient);
        try {
            initResp = payTemplate.transfer("pubkey_mch", newReq);
        } finally {
            payTemplate.setCustomHttpClient(defaultHttpClient);
        }
        assertEquals(PayChannel.WECHAT_PAY, initResp.getChannel());
        assertEquals("MCH_BILL_20261118_01", initResp.getOutBizNo());
        assertEquals("133000007110109690030000001", initResp.getOrderId());
        assertEquals(PayTransferState.WAIT_USER_CONFIRM, initResp.getStatus());
        assertEquals("MOCK_PKG_INFO_ABC", initResp.getPackageInfo());
        assertFalse(initResp.toString().contains("王五"), "PayTransferResponse.toString() 不得泄露收款人明文姓名");

        RecordedRequest recordedInit = server.takeRequest();
        assertEquals("POST", recordedInit.getMethod());
        assertEquals("/v3/fund-app/mch-transfer/transfer-bills", recordedInit.getPath());
        assertEquals("PUB_KEY_ID_TEST_001", recordedInit.getHeader("Wechatpay-Serial"));
        String initBody = recordedInit.getBody().readUtf8();
        assertTrue(initBody.contains("\"appid\":\"wx_pubkey_app\""));
        assertTrue(initBody.contains("\"out_bill_no\":\"MCH_BILL_20261118_01\""));
        assertTrue(initBody.contains("\"transfer_scene_id\":\"1000\""));
        assertTrue(initBody.contains("\"openid\":\"oUpF8uMuAJO_M2pxb1Q9zNjWeS6o\""));
        assertTrue(initBody.contains("\"transfer_amount\":200000"));
        assertTrue(initBody.contains("\"transfer_remark\":\"会员拉新现金红包\""));
        assertTrue(initBody.contains("\"transfer_scene_report_infos\""));
        assertTrue(initBody.contains("\"info_type\":\"活动名称\""));
        assertTrue(initBody.contains("\"info_content\":\"会员拉新奖励\""));
        assertTrue(initBody.contains("\"notify_url\":\"https://example.com/transfer/notify\""));
        assertTrue(initBody.contains("\"user_name\":\"") && !initBody.contains("\"user_name\":\"王五\""),
                "出站载荷中的 user_name 必须为密文");

        // 3. 按商户单号 out_bill_no 查询
        String queryByOutJson = "{\"mch_id\":\"1900000001\",\"out_bill_no\":\"MCH_BILL_20261118_01\",\"transfer_bill_no\":\"133000007110109690030000001\",\"state\":\"SUCCESS\",\"transfer_amount\":200000,\"openid\":\"oUpF8uMuAJO_M2pxb1Q9zNjWeS6o\",\"update_time\":\"2026-11-18T15:02:00+08:00\"}";
        server.enqueue(mockResponse(200, queryByOutJson));
        PayTransferResponse q1 = payTemplate.queryTransfer("MCH_BILL_20261118_01");
        assertEquals(PayTransferState.SUCCESS, q1.getStatus());
        assertEquals(new BigDecimal("2000.00"), q1.getAmount());
        assertEquals("133000007110109690030000001", q1.getOrderId());
        RecordedRequest reqQ1 = server.takeRequest();
        assertEquals("GET", reqQ1.getMethod());
        assertEquals("/v3/fund-app/mch-transfer/transfer-bills/out-bill-no/MCH_BILL_20261118_01", reqQ1.getPath());

        // 4. 按微信转账单号 transfer_bill_no 查询
        String queryByWxJson = "{\"mch_id\":\"1900000001\",\"out_bill_no\":\"MCH_BILL_20261118_01\",\"transfer_bill_no\":\"133000007110109690030000001\",\"state\":\"PROCESSING\",\"transfer_amount\":5000,\"openid\":\"oUpF8uMuAJO_M2pxb1Q9zNjWeS6o\",\"create_time\":\"2026-11-18T15:00:00+08:00\"}";
        server.enqueue(mockResponse(200, queryByWxJson));
        PayTransferResponse q2 = payTemplate.queryTransfer(null, null, "133000007110109690030000001");
        assertEquals(PayTransferState.PROCESSING, q2.getStatus());
        assertEquals(new BigDecimal("50.00"), q2.getAmount());
        RecordedRequest reqQ2 = server.takeRequest();
        assertEquals("GET", reqQ2.getMethod());
        assertEquals("/v3/fund-app/mch-transfer/transfer-bills/transfer-bill-no/133000007110109690030000001", reqQ2.getPath());

        // 5. 撤销转账 cancelTransfer
        String cancelJson = "{\"out_bill_no\":\"MCH_BILL_20261118_01\",\"transfer_bill_no\":\"133000007110109690030000001\",\"state\":\"CANCELLED\",\"update_time\":\"2026-11-18T15:03:00+08:00\"}";
        server.enqueue(mockResponse(200, cancelJson));
        PayTransferResponse cancelResp = payTemplate.cancelTransfer("MCH_BILL_20261118_01");
        assertEquals(PayTransferState.CANCELLED, cancelResp.getStatus());
        assertEquals("MCH_BILL_20261118_01", cancelResp.getOutBizNo());
        assertEquals("133000007110109690030000001", cancelResp.getOrderId());
        RecordedRequest reqCancel = server.takeRequest();
        assertEquals("POST", reqCancel.getMethod());
        assertEquals("/v3/fund-app/mch-transfer/transfer-bills/out-bill-no/MCH_BILL_20261118_01/cancel", reqCancel.getPath());
    }

    @Test
    @DisplayName("transfer: 微信单笔转账参数前置防卫校验")
    void testTransferValidationErrors() {
        payTemplate.setTransferEnabled(true);

        // 1. 空请求
        PayException ex1 = assertThrows(PayException.class, () -> payTemplate.transfer(null));
        assertEquals(PayResultCode.PAY_PARAM_INVALID, ex1.getResultCode());

        // 2. 缺失 outBatchNo
        WechatTransferRequest req2 = WechatTransferRequest.builder().amount(new BigDecimal("10.00")).openid("openid_123").build();
        PayException ex2 = assertThrows(PayException.class, () -> payTemplate.transfer(req2));
        assertEquals(PayResultCode.PAY_PARAM_INVALID, ex2.getResultCode());

        // 3. 金额小于等于 0
        WechatTransferRequest req3 = WechatTransferRequest.builder().outBatchNo("B001").amount(BigDecimal.ZERO).openid("openid_123").build();
        PayException ex3 = assertThrows(PayException.class, () -> payTemplate.transfer(req3));
        assertEquals(PayResultCode.PAY_AMOUNT_INVALID, ex3.getResultCode());

        // 4. 金额小数位超过 2 位
        WechatTransferRequest req4 = WechatTransferRequest.builder().outBatchNo("B001").amount(new BigDecimal("10.005")).openid("openid_123").build();
        PayException ex4 = assertThrows(PayException.class, () -> payTemplate.transfer(req4));
        assertEquals(PayResultCode.PAY_AMOUNT_INVALID, ex4.getResultCode());

        // 5. 缺失 openid
        WechatTransferRequest req5 = WechatTransferRequest.builder().outBatchNo("B001").amount(new BigDecimal("10.00")).build();
        PayException ex5 = assertThrows(PayException.class, () -> payTemplate.transfer(req5));
        assertEquals(PayResultCode.PAY_PARAM_INVALID, ex5.getResultCode());
    }

    @Test
    @DisplayName("bill: 微信对账单元数据查询与逃生舱验证")
    void testQueryTradeBillAndEscapeHatches() throws Exception {
        String mockResp = "{\"download_url\":\"https://api.mch.weixin.qq.com/v3/billdownload/file?token=test_token\",\"hash_type\":\"SHA1\",\"hash_value\":\"abc123456\"}";
        server.enqueue(mockResponse(200, mockResp));

        GetTradeBillRequest req = new GetTradeBillRequest();
        req.setBillDate("2026-10-24");
        req.setBillType(BillType.ALL);
        req.setTarType(TarType.GZIP);

        QueryBillEntity entity = payTemplate.queryTradeBill(req);
        assertNotNull(entity);
        assertEquals("https://api.mch.weixin.qq.com/v3/billdownload/file?token=test_token", entity.getDownloadUrl());
        assertEquals("abc123456", entity.getHashValue());

        RecordedRequest recorded = server.takeRequest();
        assertEquals("GET", recorded.getMethod());
        assertTrue(recorded.getPath().contains("/v3/bill/tradebill?bill_date=2026-10-24"));

        // 逃生舱检查
        assertNotNull(payTemplate.getBillDownloadService("default"));
        assertNotNull(payTemplate.getBillDownloadServiceExtension("default"));

        // 参数校验异常拦截
        PayException ex1 = assertThrows(PayException.class, () -> payTemplate.queryTradeBill(null));
        assertEquals(PayResultCode.PAY_PARAM_INVALID, ex1.getResultCode());

        GetTradeBillRequest emptyDateReq = new GetTradeBillRequest();
        PayException ex2 = assertThrows(PayException.class, () -> payTemplate.queryTradeBill(emptyDateReq));
        assertEquals(PayResultCode.PAY_PARAM_INVALID, ex2.getResultCode());

        PayException ex3 = assertThrows(PayException.class, () -> payTemplate.downloadBillStream(""));
        assertEquals(PayResultCode.PAY_PARAM_INVALID, ex3.getResultCode());
    }

    @Test
    @DisplayName("平台公钥模式：使用 wechatPayPublicKeyPath 加载公钥并初始化 RSAPublicKeyConfig 与 Native 扫码成功")
    void testInitWithWechatPayPublicKeyPathAndPayNative() throws Exception {
        com.wechat.pay.java.core.Config config = payTemplate.getWechatConfig("pubkey_mch");
        assertNotNull(config, "获取配置对象不应为空");
        assertInstanceOf(com.wechat.pay.java.core.RSAPublicKeyConfig.class, config,
                "配置对象必须是官方 RSAPublicKeyConfig 实例");

        // 为 pubkey_mch 注入对应的 MockHttpClient，使其使用 RSAPublicKeyConfig 进行响应验签
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
        HttpClient pubKeyHttpClient = new DefaultHttpClientBuilder()
                .config(config)
                .okHttpClient(redirectOkHttpClient)
                .build();
        payTemplate.setCustomHttpClient(pubKeyHttpClient);

        String mockResponseJson = "{\"code_url\":\"weixin://wxpay/bizpayurl?pr=mock_pubkey_qr\"}";
        server.enqueue(mockResponse(200, mockResponseJson, "PUB_KEY_ID_TEST_001"));

        WechatPayRequest request = WechatPayRequest.builder()
                .merchantKey("pubkey_mch")
                .outTradeNo("PUBKEY_20261023_001")
                .description("测试平台公钥商品-Native")
                .amount(new BigDecimal("88.88"))
                .build();

        PayResponse response = payTemplate.payNative(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals("weixin://wxpay/bizpayurl?pr=mock_pubkey_qr", response.getQrCode());
        assertEquals(new BigDecimal("88.88"), response.getAmount());
    }

    @Test
    @DisplayName("网络超时防悬挂: 微信 HttpProperties 读取超时真实作用于构建出的 HttpClient 并验证非法超时 Fail-Fast")
    void testWechatHttpTimeoutTakesEffectAndValidation() {
        PayProperties fastProps = new PayProperties();
        fastProps.getWechat().getHttp().setConnectTimeout(Duration.ofMillis(500));
        fastProps.getWechat().getHttp().setReadTimeout(Duration.ofMillis(200));
        fastProps.getWechat().getHttp().setWriteTimeout(Duration.ofMillis(500));
        fastProps.getWechat().setDefaultMerchant("fast");

        WechatMerchantProperties mch = new WechatMerchantProperties();
        mch.setMchId("1900000099");
        mch.setAppId("wx_fast_app");
        mch.setMerchantSerialNumber(testCertSerial);
        mch.setPrivateKeyPath(CLASSPATH_KEY);
        fastProps.getWechat().getMerchants().put("fast", mch);

        certificateManager.registerCertificates("1900000099", Map.of(testCertSerial, testCert));
        WechatPayTemplate fastTemplate = new WechatPayTemplate(
                new NamedMerchantRegistry(fastProps), certificateManager, fastProps);

        server.enqueue(new MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE));
        HttpClient builtClient = fastTemplate.getHttpClient("fast");
        long startNanos = System.nanoTime();
        assertThrows(com.wechat.pay.java.core.exception.HttpException.class,
                () -> builtClient.download(server.url("/v3/slow-timeout-probe").toString()));
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        assertTrue(elapsedMs < 3000, "微信 200ms 读取超时必须生效，实际耗时: " + elapsedMs + "ms");

        // 非法 <= 0 超时配置必须 Fail-Fast
        PayProperties badProps = new PayProperties();
        badProps.getWechat().getHttp().setReadTimeout(Duration.ZERO);
        badProps.getWechat().setDefaultMerchant("bad");
        badProps.getWechat().getMerchants().put("bad", mch);
        WechatPayTemplate badTemplate = new WechatPayTemplate(
                new NamedMerchantRegistry(badProps), certificateManager, badProps);
        PayException ex = assertThrows(PayException.class, () -> badTemplate.getHttpClient("bad"));
        assertEquals(PayResultCode.PAY_PARAM_INVALID, ex.getResultCode());
    }

    @Test
    @DisplayName("微信公钥ID强校验: 配置公钥但缺失 wechatPayPublicKeyId 时快速失败而非回退商户序列号")
    void testWechatPublicKeyMissingIdFailFast() {
        PayProperties props = new PayProperties();
        props.getWechat().setDefaultMerchant("pubkey_missing_id");
        WechatMerchantProperties broken = new WechatMerchantProperties();
        broken.setMchId("1900000087");
        broken.setAppId("wx_pubkey_app");
        broken.setMerchantSerialNumber(testCertSerial);
        broken.setPrivateKeyPath(CLASSPATH_KEY);
        broken.setWechatPayPublicKeyPath("classpath:certs/test_public_key.pem");
        broken.setWechatPayPublicKeyId("   ");
        props.getWechat().getMerchants().put("pubkey_missing_id", broken);

        WechatPayTemplate brokenTemplate = new WechatPayTemplate(
                new NamedMerchantRegistry(props), certificateManager, props);
        PayException ex = assertThrows(PayException.class, () -> brokenTemplate.getWechatConfig("pubkey_missing_id"));
        assertEquals(PayResultCode.PAY_PARAM_INVALID, ex.getResultCode());
    }

    @Test
    @DisplayName("自动平台证书下载与生命周期: 未预置证书时通过 /v3/certificates 下载解密并支持 destroy 清理")
    void testWechatAutoCertificateDownloadAndDestroyLifecycle() throws Exception {
        String autoMchId = "1900000088";
        PayProperties autoProps = new PayProperties();
        autoProps.getWechat().setDefaultMerchant("auto_mch");
        WechatMerchantProperties autoMch = new WechatMerchantProperties();
        autoMch.setMchId(autoMchId);
        autoMch.setAppId("wx_auto_app");
        autoMch.setMerchantSerialNumber(testCertSerial);
        autoMch.setPrivateKeyPath(CLASSPATH_KEY);
        autoMch.setApiV3Key("0123456789ABCDEF0123456789ABCDEF");
        autoMch.setNotifyUrl("https://example.com/pay/callback/wechat");
        autoMch.setAutoCertificate(true);
        autoProps.getWechat().getMerchants().put("auto_mch", autoMch);

        WechatAutoCertificateManager autoCertManager = new WechatAutoCertificateManager(Duration.ofMinutes(10));
        WechatPayTemplate autoTemplate = new WechatPayTemplate(
                new NamedMerchantRegistry(autoProps), autoCertManager, autoProps);
        try {
            okhttp3.OkHttpClient redirectOkHttpClient = new okhttp3.OkHttpClient.Builder()
                    .addInterceptor(chain -> {
                        okhttp3.Request orig = chain.request();
                        okhttp3.HttpUrl newUrl = orig.url().newBuilder()
                                .scheme("http")
                                .host(server.getHostName())
                                .port(server.getPort())
                                .build();
                        return chain.proceed(orig.newBuilder().url(newUrl).build());
                    })
                    .build();
            com.wechat.pay.java.core.auth.Credential cred = new com.wechat.pay.java.core.auth.WechatPay2Credential(
                    autoMchId, new com.wechat.pay.java.core.cipher.RSASigner(testCertSerial, testPrivateKey));
            com.wechat.pay.java.core.auth.Validator bootstrapValidator = new com.wechat.pay.java.core.auth.Validator() {
                @Override
                public <T> boolean validate(com.wechat.pay.java.core.http.HttpHeaders responseHeaders, String responseBody) {
                    return true;
                }

                @Override
                public <T> String getSerialNumber() {
                    return testCertSerial;
                }
            };
            HttpClient mockAutoClient = new DefaultHttpClientBuilder()
                    .credential(cred)
                    .validator(bootstrapValidator)
                    .okHttpClient(redirectOkHttpClient)
                    .build();
            autoTemplate.setCustomHttpClient(mockAutoClient);

            // 构造加密后的 /v3/certificates 平台证书响应（AEAD_AES_256_GCM）
            String certPem = "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(testCert.getEncoded())
                    + "\n-----END CERTIFICATE-----";
            String nonce = "cert_nonce_12";
            String associatedData = "certificate";
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(autoMch.getApiV3Key().getBytes(StandardCharsets.UTF_8), "AES"),
                    new javax.crypto.spec.GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            String ciphertext = Base64.getEncoder().encodeToString(cipher.doFinal(certPem.getBytes(StandardCharsets.UTF_8)));

            String certRespBody = "{"
                    + "\"data\":[{"
                    + "\"serial_no\":\"" + testCertSerial + "\","
                    + "\"effective_time\":\"2026-01-01T00:00:00+08:00\","
                    + "\"expire_time\":\"2030-01-01T00:00:00+08:00\","
                    + "\"encrypt_certificate\":{"
                    + "\"algorithm\":\"AEAD_AES_256_GCM\","
                    + "\"nonce\":\"" + nonce + "\","
                    + "\"associated_data\":\"" + associatedData + "\","
                    + "\"ciphertext\":\"" + ciphertext + "\""
                    + "}"
                    + "}]"
                    + "}";

            server.enqueue(mockResponse(200, certRespBody));
            server.enqueue(mockResponse(200, "{\"code_url\":\"weixin://wxpay/bizpayurl?pr=auto_cert_ok\"}"));
            server.enqueue(mockResponse(200, certRespBody));

            WechatPayRequest request = WechatPayRequest.builder()
                    .merchantKey("auto_mch")
                    .outTradeNo("AUTO_CERT_20261118_01")
                    .description("自动证书下载测试")
                    .amount(new BigDecimal("12.34"))
                    .build();

            PayResponse response = autoTemplate.payNative(request);
            assertTrue(response.isSuccess());
            assertEquals("weixin://wxpay/bizpayurl?pr=auto_cert_ok", response.getQrCode());
            assertNotNull(autoCertManager.getCertificate(autoMchId, testCertSerial));

            RecordedRequest firstReq = server.takeRequest();
            RecordedRequest secondReq = server.takeRequest();
            assertTrue(firstReq.getPath().startsWith("/v3/certificates"));
            assertTrue(secondReq.getPath().contains("/v3/pay/transactions/native"));

            // 触发证书刷新更新并验证 HTTP 模拟桩再次拉取
            autoCertManager.refreshCertificate(autoMchId);
            RecordedRequest refreshReq = server.takeRequest();
            assertTrue(refreshReq.getPath().startsWith("/v3/certificates"));
            assertNotNull(autoCertManager.getAvailableCertificate(autoMchId));

            // 销毁生命周期后校验缓存与任务已彻底清理
            autoCertManager.destroy();
            assertFalse(autoCertManager.hasCertificates(autoMchId));
            assertThrows(io.github.atengk.boot.pay.exception.PaySignException.class,
                    () -> autoCertManager.getAvailableCertificate(autoMchId));
        } finally {
            autoCertManager.destroy();
        }
    }

    @Test
    @DisplayName("cancelOrder: reverseRetry.enabled=false 时仅单次调用不重试，且默认退避配置对齐 1s/2.0/5s/3次")
    void testCancelOrder_retryDisabledAndDefaultExponentialConfig() throws Exception {
        WechatPayTemplate defaultTemplate = new WechatPayTemplate(merchantRegistry, certificateManager);
        io.github.atengk.boot.core.util.RetryUtil.RetryConfig defaultConfig = defaultTemplate.getWechatReverseRetryConfig();
        assertEquals(3, defaultConfig.maxAttempts());
        assertEquals(Duration.ofSeconds(1), defaultConfig.delay());
        assertEquals(2.0, defaultConfig.multiplier());
        assertEquals(Duration.ofSeconds(5), defaultConfig.maxDelay());

        PayProperties disabledProps = new PayProperties();
        disabledProps.getWechat().getReverseRetry().setEnabled(false);
        disabledProps.getWechat().getReverseRetry().setMaxAttempts(3);
        disabledProps.getWechat().getReverseRetry().setDelay(Duration.ofMillis(10));
        disabledProps.getWechat().getReverseRetry().setMaxDelay(Duration.ofMillis(10));
        WechatPayTemplate noRetryTemplate = new WechatPayTemplate(merchantRegistry, certificateManager, disabledProps);
        noRetryTemplate.setCustomHttpClient(payTemplate.getHttpClient());

        server.enqueue(mockResponse(500, "{\"code\":\"SYSTEMERROR\",\"message\":\"系统繁忙\"}"));

        PayTradeException ex = assertThrows(PayTradeException.class,
                () -> noRetryTemplate.cancelOrder("CANCEL_NO_RETRY_001"));
        assertEquals("SYSTEMERROR", ex.getRawErrorCode());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    @DisplayName("cancelOrder: 结构化响应解析(CLOSED)与 recall=Y 受控重试闭环")
    void testCancelOrder_structuredResponseAndRecallRetry() throws Exception {
        // 1. HTTP 200 结构化响应体直接映射 CLOSED 与描述
        String closedJson = """
                {
                  "out_trade_no": "CANCEL_STRUCT_001",
                  "trade_state": "CLOSED",
                  "trade_state_desc": "未支付已安全关闭",
                  "recall": "N"
                }
                """;
        server.enqueue(mockResponse(200, closedJson));

        PayResponse closedResp = payTemplate.cancelOrder("CANCEL_STRUCT_001");
        assertTrue(closedResp.isSuccess());
        assertEquals(PayTradeState.CLOSED, closedResp.getTradeState());
        assertEquals("未支付已安全关闭", closedResp.getTradeStateDesc());
        assertNotNull(closedResp.getRawResponse());

        // 2. recall=Y 触发退避重试并在第二次成功
        String recallJson = "{\"out_trade_no\":\"CANCEL_RECALL_002\",\"recall\":\"Y\"}";
        server.enqueue(mockResponse(200, recallJson));
        server.enqueue(mockResponse(204, null));

        PayResponse recallSuccessResp = payTemplate.cancelOrder("CANCEL_RECALL_002");
        assertTrue(recallSuccessResp.isSuccess());
        assertEquals(PayTradeState.REVOKED, recallSuccessResp.getTradeState());
        assertEquals(3, server.getRequestCount());

        // 3. recall=Y 耗尽次数后清晰转译业务异常
        server.enqueue(mockResponse(200, recallJson));
        server.enqueue(mockResponse(200, recallJson));
        server.enqueue(mockResponse(200, recallJson));

        PayTradeException recallEx = assertThrows(PayTradeException.class,
                () -> payTemplate.cancelOrder("CANCEL_RECALL_002"));
        assertEquals("REVERSE_RECALL_REQUIRED", recallEx.getRawErrorCode());
    }

    @Test
    @DisplayName("cancelOrder: 安全兜底策略验证(未支付关单 / 查单不存在幂等 / 已支付 REJECT 与 AUTO_REFUND / ORDER_NOT_EXIST 不误触发)")
    void testCancelOrder_safeFallbackPolicies() throws Exception {
        // 1. 开启兜底且 paidPolicy=REJECT（默认）：未支付 -> 查单 -> 关单
        PayProperties rejectFallbackProps = new PayProperties();
        rejectFallbackProps.getWechat().getReverseRetry().setDelay(Duration.ofMillis(10));
        rejectFallbackProps.getWechat().getReverseRetry().setMaxDelay(Duration.ofMillis(10));
        rejectFallbackProps.getWechat().getReverseFallback().setEnabled(true);
        rejectFallbackProps.getWechat().getReverseFallback().setPaidPolicy(
                WechatPayProperties.ReverseFallbackProperties.PaidPolicy.REJECT);
        WechatPayTemplate rejectTemplate = new WechatPayTemplate(merchantRegistry, certificateManager, rejectFallbackProps);
        rejectTemplate.setCustomHttpClient(payTemplate.getHttpClient());

        server.enqueue(mockResponse(404, "{\"code\":\"RESOURCE_NOT_EXISTS\",\"message\":\"接口未开放\"}"));
        server.enqueue(mockResponse(200, "{\"out_trade_no\":\"FB_NOTPAY_01\",\"trade_state\":\"NOTPAY\",\"trade_state_desc\":\"未支付\"}"));
        server.enqueue(mockResponse(204, null));

        PayResponse closedResp = rejectTemplate.cancelOrder("FB_NOTPAY_01");
        assertTrue(closedResp.isSuccess());
        assertEquals(PayTradeState.CLOSED, closedResp.getTradeState());
        assertEquals("/v3/pay/transactions/out-trade-no/FB_NOTPAY_01/reverse", server.takeRequest().getPath());
        assertEquals("/v3/pay/transactions/out-trade-no/FB_NOTPAY_01?mchid=1900000001", server.takeRequest().getPath());
        assertEquals("/v3/pay/transactions/out-trade-no/FB_NOTPAY_01/close", server.takeRequest().getPath());

        // 2. 开启兜底：查单返回 ORDER_NOT_EXIST 时幂等视同已关闭
        server.enqueue(mockResponse(404, "{\"code\":\"RESOURCE_NOT_EXISTS\",\"message\":\"接口未开放\"}"));
        server.enqueue(mockResponse(404, "{\"code\":\"ORDER_NOT_EXIST\",\"message\":\"订单不存在\"}"));
        PayResponse notExistResp = rejectTemplate.cancelOrder("FB_MISSING_02");
        assertTrue(notExistResp.isSuccess());
        assertEquals(PayTradeState.CLOSED, notExistResp.getTradeState());
        server.takeRequest();
        server.takeRequest();

        // 3. 开启兜底且 paidPolicy=REJECT：已支付 -> 拒绝自动退款
        server.enqueue(mockResponse(501, "{\"code\":\"NOT_IMPLEMENTED\",\"message\":\"不支持撤销端点\"}"));
        server.enqueue(mockResponse(200, "{\"out_trade_no\":\"FB_PAID_03\",\"transaction_id\":\"WX_PAID_03\",\"trade_state\":\"SUCCESS\",\"amount\":{\"total\":2500,\"currency\":\"CNY\"}}"));
        PayTradeException rejectEx = assertThrows(PayTradeException.class, () -> rejectTemplate.cancelOrder("FB_PAID_03"));
        assertEquals("ORDER_PAID_REVERSE_REJECTED", rejectEx.getRawErrorCode());
        assertEquals("/v3/pay/transactions/out-trade-no/FB_PAID_03/reverse", server.takeRequest().getPath());
        assertEquals("/v3/pay/transactions/out-trade-no/FB_PAID_03?mchid=1900000001", server.takeRequest().getPath());

        // 4. 开启兜底且 /reverse 本身返回 ORDER_NOT_EXIST -> 不触发兜底，直接抛 PAY_ORDER_NOT_FOUND
        server.enqueue(mockResponse(404, "{\"code\":\"ORDER_NOT_EXIST\",\"message\":\"此交易订单号不存在\"}"));
        PayTradeException orderMissingEx = assertThrows(PayTradeException.class, () -> rejectTemplate.cancelOrder("FB_404_04"));
        assertEquals(PayResultCode.PAY_ORDER_NOT_FOUND, orderMissingEx.getResultCode());
        server.takeRequest();

        // 5. 开启兜底且 paidPolicy=AUTO_REFUND：已支付 -> 自动全额退款
        PayProperties refundFallbackProps = new PayProperties();
        refundFallbackProps.getWechat().getReverseRetry().setDelay(Duration.ofMillis(10));
        refundFallbackProps.getWechat().getReverseRetry().setMaxDelay(Duration.ofMillis(10));
        refundFallbackProps.getWechat().getReverseFallback().setEnabled(true);
        refundFallbackProps.getWechat().getReverseFallback().setPaidPolicy(
                WechatPayProperties.ReverseFallbackProperties.PaidPolicy.AUTO_REFUND);
        WechatPayTemplate autoRefundTemplate = new WechatPayTemplate(merchantRegistry, certificateManager, refundFallbackProps);
        autoRefundTemplate.setCustomHttpClient(payTemplate.getHttpClient());

        server.enqueue(mockResponse(404, "{\"code\":\"RESOURCE_NOT_EXISTS\",\"message\":\"接口未开放\"}"));
        server.enqueue(mockResponse(200, "{\"out_trade_no\":\"FB_REFUND_05\",\"transaction_id\":\"WX_REFUND_05\",\"trade_state\":\"SUCCESS\",\"amount\":{\"total\":1234,\"currency\":\"CNY\"}}"));
        server.enqueue(mockResponse(200, "{\"refund_id\":\"500000999\",\"out_refund_no\":\"REV_FB_REFUND_05\",\"transaction_id\":\"WX_REFUND_05\",\"out_trade_no\":\"FB_REFUND_05\",\"status\":\"PROCESSING\",\"amount\":{\"total\":1234,\"refund\":1234,\"currency\":\"CNY\"}}"));

        PayResponse autoRefundResp = autoRefundTemplate.cancelOrder("FB_REFUND_05");
        assertTrue(autoRefundResp.isSuccess());
        assertEquals(PayTradeState.REFUND, autoRefundResp.getTradeState());
        assertEquals(new BigDecimal("12.34"), autoRefundResp.getAmount());
        server.takeRequest();
        server.takeRequest();
        RecordedRequest refundReq = server.takeRequest();
        assertEquals("/v3/refund/domestic/refunds", refundReq.getPath());
        String refundBody = refundReq.getBody().readUtf8();
        assertTrue(refundBody.contains("\"out_refund_no\":\"REV_FB_REFUND_05\""));
        assertTrue(refundBody.contains("\"refund\":1234"));
    }

    @Test
    @DisplayName("服务商模式 (Partner): 条码付、撤销冲正（含 serviceProviderMode 全局开关）与对账单自动透传 sub_mchid")
    void testPartnerMode_barcodeCancelAndTradeBill() throws Exception {
        // 1. 显式使用 partner_tenant 发起服务商条码付
        server.enqueue(mockResponse(200, """
                {
                  "sp_mchid": "1900000000",
                  "sp_appid": "wx_sp_app",
                  "sub_mchid": "1900000002",
                  "sub_appid": "wx_sub_app",
                  "out_trade_no": "P_BAR_001",
                  "transaction_id": "4200009999",
                  "trade_type": "MICROPAY",
                  "trade_state": "SUCCESS",
                  "amount": {"total": 1500, "currency": "CNY"}
                }
                """));
        PayResponse barcodeResp = payTemplate.payBarcode(WechatPayRequest.builder()
                .merchantKey("partner_tenant")
                .outTradeNo("P_BAR_001")
                .description("服务商条码付")
                .amount(new BigDecimal("15.00"))
                .authCode("134567890123456789")
                .build());
        assertTrue(barcodeResp.isSuccess());
        assertEquals(PayTradeState.SUCCESS, barcodeResp.getTradeState());
        RecordedRequest barcodeReq = server.takeRequest();
        assertEquals("/v3/pay/partner/transactions/codepay", barcodeReq.getPath());
        String barcodeBody = barcodeReq.getBody().readUtf8();
        assertTrue(barcodeBody.contains("\"sp_appid\":\"wx_sp_app\""));
        assertTrue(barcodeBody.contains("\"sp_mchid\":\"1900000000\""));
        assertTrue(barcodeBody.contains("\"sub_mchid\":\"1900000002\""));
        assertTrue(barcodeBody.contains("\"sub_appid\":\"wx_sub_app\""));

        // 2. 服务商模式下对账单查询与下载自动透传 sub_mchid 查询参数
        server.enqueue(mockResponse(200, "{\"download_url\":\"https://api.mch.weixin.qq.com/v3/billdownload/file?token=partner_tok\",\"hash_type\":\"SHA1\",\"hash_value\":\"h_partner\"}"));
        GetTradeBillRequest billReq = new GetTradeBillRequest();
        billReq.setBillDate("2026-11-18");
        billReq.setBillType(BillType.ALL);
        billReq.setTarType(TarType.GZIP);
        QueryBillEntity billMeta = payTemplate.queryTradeBill("partner_tenant", billReq);
        assertEquals("h_partner", billMeta.getHashValue());
        RecordedRequest billRecorded = server.takeRequest();
        assertTrue(billRecorded.getPath().contains("/v3/bill/tradebill?"));
        assertTrue(billRecorded.getPath().contains("sub_mchid=1900000002"));

        // 3. 全局开启 serviceProviderMode=true 且商户仅配置 mchId + subMchId 时自动按服务商路由
        PayProperties spProps = new PayProperties();
        spProps.getWechat().setServiceProviderMode(true);
        spProps.getWechat().setDefaultMerchant("sp_only");
        WechatMerchantProperties spOnlyMch = new WechatMerchantProperties();
        spOnlyMch.setMchId("1900000001");
        spOnlyMch.setAppId("wx_sp_global_app");
        spOnlyMch.setSubMchId("1900000888");
        spOnlyMch.setMerchantSerialNumber(testCertSerial);
        spOnlyMch.setPrivateKeyPath(CLASSPATH_KEY);
        spProps.getWechat().setMerchants(Map.of("sp_only", spOnlyMch));
        WechatPayTemplate spTemplate = new WechatPayTemplate(new NamedMerchantRegistry(spProps), certificateManager, spProps);
        spTemplate.setCustomHttpClient(payTemplate.getHttpClient());

        server.enqueue(mockResponse(204, null));
        PayResponse spCancelResp = spTemplate.cancelOrder("SP_GLOBAL_REV_01");
        assertTrue(spCancelResp.isSuccess());
        RecordedRequest spCancelReq = server.takeRequest();
        assertEquals("/v3/pay/partner/transactions/out-trade-no/SP_GLOBAL_REV_01/reverse", spCancelReq.getPath());
        String spCancelBody = spCancelReq.getBody().readUtf8();
        assertTrue(spCancelBody.contains("\"sp_mchid\":\"1900000001\""));
        assertTrue(spCancelBody.contains("\"sub_mchid\":\"1900000888\""));
    }

    // =========================================================================
    // 辅助工具方法
    // =========================================================================

    /**
     * 构造携带微信支付标准验签标头的 MockResponse
     */
    private MockResponse mockResponse(int statusCode, String body) throws Exception {
        return mockResponse(statusCode, body, testCertSerial);
    }

    /**
     * 构造携带指定证书/公钥序列号的 MockResponse
     */
    private MockResponse mockResponse(int statusCode, String body, String serialNumber) throws Exception {
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
                .setHeader("Wechatpay-Serial", serialNumber);

        if (body != null) {
            response.setBody(body);
        }
        return response;
    }
}
