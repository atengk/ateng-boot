package io.github.atengk.boot.pay.core;

import io.github.atengk.boot.pay.config.properties.AlipayAppProperties;
import io.github.atengk.boot.pay.config.properties.AlipayPayProperties;
import io.github.atengk.boot.pay.config.properties.PayProperties;
import io.github.atengk.boot.pay.core.merchant.NamedMerchantRegistry;
import io.github.atengk.boot.pay.enums.PayChannel;
import io.github.atengk.boot.pay.enums.PayResultCode;
import io.github.atengk.boot.pay.enums.PayTradeState;
import io.github.atengk.boot.pay.enums.PayTradeType;
import io.github.atengk.boot.pay.exception.PayException;
import io.github.atengk.boot.pay.exception.PayNetworkException;
import io.github.atengk.boot.pay.exception.PaySignException;
import io.github.atengk.boot.pay.exception.PayTradeException;
import io.github.atengk.boot.pay.model.request.AlipayPayRequest;
import io.github.atengk.boot.pay.model.response.PayResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 支付宝专有门面 AlipayTemplate 100% 离线自洽单元测试
 * <p>
 * 基于 OkHttp MockWebServer 拦截所有出站请求，验证：
 * 1. 电脑网站（Page）、手机网站（Wap）、App 客户端支付的本地表单与加签调起串组装；
 * 2. 当面付条码收单（Barcode）、扫码预下单（Qr）的出站请求与签名解析；
 * 3. 查单（queryOrder）、关单（closeOrder）、撤销（cancelOrder）的状态机映射与单标识重载；
 * 4. 服务商（ISV）模式 app_auth_token 透传；
 * 5. 异常因果链转译（验签失败、网络异常、Fail-Fast 校验、订单不存在等）。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
class AlipayTemplateTest {

    private MockWebServer server;
    private NamedMerchantRegistry merchantRegistry;
    private AlipayTemplate payTemplate;

    private String merchantPrivateKeyPem;
    private PrivateKey alipayPrivateKey;
    private String alipayPublicKeyPem;

    @BeforeEach
    void setUp() throws Exception {
        // 1. 启动纯内存 MockWebServer
        server = new MockWebServer();
        server.start();

        // 2. 动态生成商户公私钥对与支付宝公私钥对（确保单测 100% 独立离线自洽）
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);

        // 商户密钥对
        KeyPair merchantKeyPair = kpg.generateKeyPair();
        merchantPrivateKeyPem = Base64.getEncoder().encodeToString(merchantKeyPair.getPrivate().getEncoded());

        // 支付宝密钥对（私钥用于 Mock 网关签名，公钥配置给 SDK 验签）
        KeyPair alipayKeyPair = kpg.generateKeyPair();
        alipayPrivateKey = alipayKeyPair.getPrivate();
        alipayPublicKeyPem = Base64.getEncoder().encodeToString(alipayKeyPair.getPublic().getEncoded());

        // 3. 配置默认支付宝应用与服务商应用
        PayProperties payProperties = new PayProperties();
        AlipayPayProperties alipay = payProperties.getAlipay();
        alipay.setDefaultApp("default");

        Map<String, AlipayAppProperties> apps = new HashMap<>();

        // 默认应用
        AlipayAppProperties defaultApp = new AlipayAppProperties();
        defaultApp.setAppId("2021000000000001");
        defaultApp.setPrivateKey(merchantPrivateKeyPem);
        defaultApp.setAlipayPublicKey(alipayPublicKeyPem);
        defaultApp.setServerUrl(server.url("/gateway.do").toString());
        defaultApp.setNotifyUrl("https://api.test.com/notify");
        defaultApp.setReturnUrl("https://api.test.com/return");
        apps.put("default", defaultApp);

        // 命名多应用 (app2)
        AlipayAppProperties app2 = new AlipayAppProperties();
        app2.setAppId("2021000000000002");
        app2.setPrivateKey(merchantPrivateKeyPem);
        app2.setAlipayPublicKey(alipayPublicKeyPem);
        app2.setServerUrl(server.url("/gateway.do").toString());
        app2.setNotifyUrl("https://api.test.com/notify2");
        apps.put("app2", app2);

        alipay.setApps(apps);

        // 4. 初始化注册表与门面
        merchantRegistry = new NamedMerchantRegistry(payProperties);
        payTemplate = new AlipayTemplate(merchantRegistry);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) {
            server.shutdown();
        }
    }

    // =========================================================================
    // 1. 电脑网站支付 (payPage) 与手机网站支付 (payWap)
    // =========================================================================

    @Test
    @DisplayName("电脑网站支付 payPage: 成功组装自提交 HTML 表单脚本与标准出参")
    void testPayPage_generatesAutoSubmitHtmlForm() {
        AlipayPayRequest request = AlipayPayRequest.builder()
                .outTradeNo("PAGE_ORDER_1001")
                .subject("MacBook Pro 16")
                .amount(new BigDecimal("18999.00"))
                .build();

        PayResponse response = payTemplate.payPage(request);

        assertNotNull(response);
        assertEquals(PayChannel.ALIPAY, response.getChannel());
        assertEquals(PayTradeType.PAGE, response.getTradeType());
        assertEquals("PAGE_ORDER_1001", response.getOutTradeNo());
        assertEquals(new BigDecimal("18999.00"), response.getAmount());
        assertTrue(response.isSuccess());
        assertEquals(PayTradeState.NOTPAY, response.getTradeState());

        String formHtml = response.getFormHtml();
        assertNotNull(formHtml);
        assertTrue(formHtml.contains("<form name=\"punchout_form\"") || formHtml.contains("<form"));
        assertTrue(formHtml.contains("PAGE_ORDER_1001"));
        assertTrue(formHtml.contains("18999.00"));
        assertTrue(formHtml.contains("FAST_INSTANT_TRADE_PAY"));
        assertTrue(formHtml.contains("document.forms[0].submit();") || formHtml.contains(".submit();"));
    }

    @Test
    @DisplayName("手机网站支付 payWap: 成功组装自提交 HTML 表单脚本与 H5 出参")
    void testPayWap_generatesAutoSubmitHtmlForm() {
        AlipayPayRequest request = AlipayPayRequest.builder()
                .outTradeNo("WAP_ORDER_1002")
                .subject("AirPods Pro 2")
                .amount(new BigDecimal("1899.50"))
                .build();

        PayResponse response = payTemplate.payWap(request);

        assertNotNull(response);
        assertEquals(PayChannel.ALIPAY, response.getChannel());
        assertEquals(PayTradeType.H5, response.getTradeType());
        assertEquals("WAP_ORDER_1002", response.getOutTradeNo());
        assertEquals(new BigDecimal("1899.50"), response.getAmount());
        assertTrue(response.isSuccess());
        assertEquals(PayTradeState.NOTPAY, response.getTradeState());

        String formHtml = response.getFormHtml();
        assertNotNull(formHtml);
        assertTrue(formHtml.contains("WAP_ORDER_1002"));
        assertTrue(formHtml.contains("1899.50"));
        assertTrue(formHtml.contains("QUICK_WAP_WAY"));
    }

    // =========================================================================
    // 2. App 支付 (payApp)
    // =========================================================================

    @Test
    @DisplayName("App 客户端支付 payApp: 成功生成已签名的 SDK 调起串 orderString")
    void testPayApp_generatesSignedOrderString() {
        AlipayPayRequest request = AlipayPayRequest.builder()
                .outTradeNo("APP_ORDER_1003")
                .subject("iPad Pro 13")
                .amount(new BigDecimal("8999.00"))
                .build();

        PayResponse response = payTemplate.payApp(request);

        assertNotNull(response);
        assertEquals(PayChannel.ALIPAY, response.getChannel());
        assertEquals(PayTradeType.APP, response.getTradeType());
        assertEquals("APP_ORDER_1003", response.getOutTradeNo());
        assertTrue(response.isSuccess());
        assertEquals(PayTradeState.NOTPAY, response.getTradeState());

        String orderString = response.getOrderString();
        assertNotNull(orderString);
        assertTrue(orderString.contains("alipay.trade.app.pay"));
        assertTrue(orderString.contains("APP_ORDER_1003"));
        assertTrue(orderString.contains("sign="));
        assertTrue(orderString.contains("sign_type=RSA2"));
    }

    // =========================================================================
    // 3. 当面付条码收单 (payBarcode)
    // =========================================================================

    @Test
    @DisplayName("条码支付 payBarcode 成功: 映射为 SUCCESS 并提取 transactionId")
    void testPayBarcode_success() throws Exception {
        String nodeJson = "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"202610232200140001\",\"out_trade_no\":\"BARCODE_ORDER_1004\",\"total_amount\":\"88.00\"}";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(buildSignedResponse("alipay_trade_pay_response", nodeJson, alipayPrivateKey)));

        AlipayPayRequest request = AlipayPayRequest.builder()
                .outTradeNo("BARCODE_ORDER_1004")
                .subject("星巴克咖啡")
                .amount(new BigDecimal("88.00"))
                .authCode("281234567890123456")
                .build();

        PayResponse response = payTemplate.payBarcode(request);

        assertNotNull(response);
        assertEquals(PayChannel.ALIPAY, response.getChannel());
        assertEquals(PayTradeType.BARCODE, response.getTradeType());
        assertEquals("BARCODE_ORDER_1004", response.getOutTradeNo());
        assertEquals("202610232200140001", response.getTransactionId());
        assertEquals(PayTradeState.SUCCESS, response.getTradeState());
        assertTrue(response.isSuccess());

        RecordedRequest recorded = server.takeRequest();
        assertEquals("POST", recorded.getMethod());
        String requestBody = java.net.URLDecoder.decode(recorded.getBody().readUtf8(), StandardCharsets.UTF_8);
        assertTrue(requestBody.contains("auth_code") && requestBody.contains("281234567890123456"));
    }

    @Test
    @DisplayName("条码支付 payBarcode 支付中: code 为 10003 时映射为 USERPAYING")
    void testPayBarcode_userPaying() throws Exception {
        String nodeJson = "{\"code\":\"10003\",\"msg\":\"Wait Buyer Pay\",\"out_trade_no\":\"BARCODE_ORDER_1005\",\"trade_no\":\"202610232200140002\"}";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(buildSignedResponse("alipay_trade_pay_response", nodeJson, alipayPrivateKey)));

        AlipayPayRequest request = AlipayPayRequest.builder()
                .outTradeNo("BARCODE_ORDER_1005")
                .subject("便利店结算")
                .amount(new BigDecimal("25.00"))
                .authCode("289999999999999999")
                .build();

        PayResponse response = payTemplate.payBarcode(request);

        assertNotNull(response);
        assertEquals(PayTradeState.USERPAYING, response.getTradeState());
        assertEquals("10003", response.getRawTradeState());
        assertTrue(response.isSuccess());
        assertEquals("202610232200140002", response.getTransactionId());
        assertTrue(response.getTradeStateDesc().contains("等待用户输入支付密码"));
    }

    // =========================================================================
    // 4. 当面付扫码预下单 (payQr)
    // =========================================================================

    @Test
    @DisplayName("扫码预下单 payQr: 成功获取 qrCode 二维码并映射为 NATIVE")
    void testPayQr_success() throws Exception {
        String nodeJson = "{\"code\":\"10000\",\"msg\":\"Success\",\"out_trade_no\":\"QR_ORDER_1006\",\"qr_code\":\"https://qr.alipay.com/bax012345678\"}";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(buildSignedResponse("alipay_trade_precreate_response", nodeJson, alipayPrivateKey)));

        AlipayPayRequest request = AlipayPayRequest.builder()
                .outTradeNo("QR_ORDER_1006")
                .subject("自助售货机饮料")
                .amount(new BigDecimal("5.50"))
                .build();

        PayResponse response = payTemplate.payQr(request);

        assertNotNull(response);
        assertEquals(PayChannel.ALIPAY, response.getChannel());
        assertEquals(PayTradeType.NATIVE, response.getTradeType());
        assertEquals("QR_ORDER_1006", response.getOutTradeNo());
        assertEquals("https://qr.alipay.com/bax012345678", response.getQrCode());
        assertEquals(PayTradeState.NOTPAY, response.getTradeState());
        assertTrue(response.isSuccess());
    }

    // =========================================================================
    // 5. 订单状态主动查询 (queryOrder)
    // =========================================================================

    @Test
    @DisplayName("查单 queryOrder: TRADE_SUCCESS 正确转换为统一状态 SUCCESS")
    void testQueryOrder_tradeSuccess() throws Exception {
        String nodeJson = "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"202610232200140003\",\"out_trade_no\":\"QUERY_1007\",\"trade_status\":\"TRADE_SUCCESS\",\"total_amount\":\"100.00\"}";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(buildSignedResponse("alipay_trade_query_response", nodeJson, alipayPrivateKey)));

        PayResponse response = payTemplate.queryOrder("QUERY_1007");

        assertNotNull(response);
        assertEquals(PayTradeState.SUCCESS, response.getTradeState());
        assertEquals("TRADE_SUCCESS", response.getRawTradeState());
        assertEquals("202610232200140003", response.getTransactionId());
        assertEquals(new BigDecimal("100.00"), response.getAmount());
        assertTrue(response.isSuccess());
    }

    @Test
    @DisplayName("查单 queryOrder: WAIT_BUYER_PAY 与 TRADE_CLOSED 状态正确转换")
    void testQueryOrder_otherStates() throws Exception {
        // 1. WAIT_BUYER_PAY
        String nodeJson1 = "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"202610232200140004\",\"out_trade_no\":\"QUERY_1008\",\"trade_status\":\"WAIT_BUYER_PAY\",\"total_amount\":\"50.00\"}";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(buildSignedResponse("alipay_trade_query_response", nodeJson1, alipayPrivateKey)));

        PayResponse response1 = payTemplate.queryOrder("QUERY_1008");
        assertEquals(PayTradeState.NOTPAY, response1.getTradeState());
        assertFalse(response1.isSuccess());

        // 2. TRADE_CLOSED (多应用重载)
        String nodeJson2 = "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"202610232200140005\",\"out_trade_no\":\"QUERY_1009\",\"trade_status\":\"TRADE_CLOSED\",\"total_amount\":\"50.00\"}";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(buildSignedResponse("alipay_trade_query_response", nodeJson2, alipayPrivateKey)));

        PayResponse response2 = payTemplate.queryOrder("app2", "QUERY_1009");
        assertEquals(PayTradeState.CLOSED, response2.getTradeState());
        assertFalse(response2.isSuccess());
    }

    @Test
    @DisplayName("查单 queryOrder: ACQ.TRADE_NOT_EXIST 转译为 PAY_ORDER_NOT_FOUND")
    void testQueryOrder_tradeNotExist_throwsException() throws Exception {
        String nodeJson = "{\"code\":\"40004\",\"msg\":\"Business Failed\",\"sub_code\":\"ACQ.TRADE_NOT_EXIST\",\"sub_msg\":\"交易不存在\"}";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(buildSignedResponse("alipay_trade_query_response", nodeJson, alipayPrivateKey)));

        PayTradeException ex = assertThrows(PayTradeException.class, () -> payTemplate.queryOrder("NON_EXIST_ORDER"));
        assertEquals(PayResultCode.PAY_ORDER_NOT_FOUND, ex.getResultCode());
        assertEquals("ACQ.TRADE_NOT_EXIST", ex.getRawErrorCode());
        assertTrue(ex.getRawErrorMessage().contains("交易不存在"));
    }

    // =========================================================================
    // 6. 关单 (closeOrder) 与 撤销 (cancelOrder)
    // =========================================================================

    @Test
    @DisplayName("关闭订单 closeOrder: 成功映射为 CLOSED 状态并支持单标识重载")
    void testCloseOrder_success() throws Exception {
        String nodeJson = "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"202610232200140006\",\"out_trade_no\":\"CLOSE_1010\"}";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(buildSignedResponse("alipay_trade_close_response", nodeJson, alipayPrivateKey)));

        PayResponse response = payTemplate.closeOrder("CLOSE_1010");

        assertNotNull(response);
        assertEquals(PayTradeState.CLOSED, response.getTradeState());
        assertTrue(response.isSuccess());
        assertEquals("CLOSE_1010", response.getOutTradeNo());
    }

    @Test
    @DisplayName("撤销订单 cancelOrder: 成功映射为 REVOKED 状态并支持单标识重载")
    void testCancelOrder_success() throws Exception {
        String nodeJson = "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"202610232200140007\",\"out_trade_no\":\"CANCEL_1011\",\"action\":\"close\"}";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(buildSignedResponse("alipay_trade_cancel_response", nodeJson, alipayPrivateKey)));

        PayResponse response = payTemplate.cancelOrder("CANCEL_1011");

        assertNotNull(response);
        assertEquals(PayTradeState.REVOKED, response.getTradeState());
        assertTrue(response.isSuccess());
        assertEquals("CANCEL_1011", response.getOutTradeNo());
        assertTrue(response.getTradeStateDesc().contains("action=close"));
    }

    // =========================================================================
    // 7. 服务商 ISV 模式与 app_auth_token 透传
    // =========================================================================

    @Test
    @DisplayName("服务商模式: 显式指定 appAuthToken 透传至请求参数中")
    void testServiceProvider_appAuthToken() throws Exception {
        String nodeJson = "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"202610232200140008\",\"out_trade_no\":\"ISV_1012\",\"total_amount\":\"10.00\"}";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(buildSignedResponse("alipay_trade_pay_response", nodeJson, alipayPrivateKey)));

        AlipayPayRequest request = AlipayPayRequest.builder()
                .outTradeNo("ISV_1012")
                .subject("服务商特约商户收单")
                .amount(new BigDecimal("10.00"))
                .authCode("283333333333333333")
                .appAuthToken("app_auth_token_sample_123456")
                .build();

        PayResponse response = payTemplate.payBarcode(request);
        assertTrue(response.isSuccess());

        RecordedRequest recorded = server.takeRequest();
        String body = recorded.getBody().readUtf8();
        assertTrue(body.contains("app_auth_token=app_auth_token_sample_123456"));
    }

    // =========================================================================
    // 8. 参数防卫与异常因果转译 (Fail-Fast & Resilience)
    // =========================================================================

    @Test
    @DisplayName("入参防卫 Fail-Fast: 空订单号、空标题、非法金额与空条码拦截")
    void testValidationFailFast() {
        // 1. 空请求
        assertThrows(PayException.class, () -> payTemplate.payPage(null));

        // 2. 空订单号
        assertThrows(PayException.class, () -> payTemplate.payPage(AlipayPayRequest.builder()
                .subject("商品")
                .amount(new BigDecimal("10.00"))
                .build()));

        // 3. 空标题
        assertThrows(PayException.class, () -> payTemplate.payPage(AlipayPayRequest.builder()
                .outTradeNo("ORDER_001")
                .amount(new BigDecimal("10.00"))
                .build()));

        // 4. 金额小于等于 0
        PayException amountEx = assertThrows(PayException.class, () -> payTemplate.payPage(AlipayPayRequest.builder()
                .outTradeNo("ORDER_001")
                .subject("商品")
                .amount(new BigDecimal("0.00"))
                .build()));
        assertEquals(PayResultCode.PAY_AMOUNT_INVALID, amountEx.getResultCode());

        // 5. 条码付缺失 authCode
        assertThrows(PayException.class, () -> payTemplate.payBarcode(AlipayPayRequest.builder()
                .outTradeNo("ORDER_001")
                .subject("商品")
                .amount(new BigDecimal("10.00"))
                .build()));

        // 6. 查单缺失 outTradeNo 和 tradeNo
        assertThrows(PayException.class, () -> payTemplate.queryOrder(AlipayPayRequest.builder().build()));
    }

    @Test
    @DisplayName("验签失败防卫: 网关返回篡改签名时转译为 PaySignException")
    void testSignatureVerificationFailure() throws Exception {
        String nodeJson = "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"202610232200140009\",\"out_trade_no\":\"TAMPER_1013\",\"total_amount\":\"10.00\"}";
        // 使用另一对无关的 RSA 私钥计算合法格式但公钥不匹配的签名
        KeyPair otherKeyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        String corruptedSign = sign(nodeJson, otherKeyPair.getPrivate());
        String responseBody = "{\"alipay_trade_pay_response\":" + nodeJson + ",\"sign\":\"" + corruptedSign + "\"}";

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(responseBody));

        AlipayPayRequest request = AlipayPayRequest.builder()
                .outTradeNo("TAMPER_1013")
                .subject("防篡改测试")
                .amount(new BigDecimal("10.00"))
                .authCode("284444444444444444")
                .build();

        assertThrows(PaySignException.class, () -> payTemplate.payBarcode(request));
    }

    @Test
    @DisplayName("网络异常防卫: 网关返回 500 错误时转译为 PayNetworkException")
    void testNetworkExceptionTranslation() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("Internal Server Error"));

        AlipayPayRequest request = AlipayPayRequest.builder()
                .outTradeNo("NET_ERR_1014")
                .subject("网络异常测试")
                .amount(new BigDecimal("10.00"))
                .authCode("285555555555555555")
                .build();

        assertThrows(PayNetworkException.class, () -> payTemplate.payBarcode(request));
    }

    @Test
    @DisplayName("逃生舱与属性获取验证: getAlipayClient 与 getAppProperties")
    void testEscapesAndAppProperties() {
        assertNotNull(payTemplate.getAlipayClient());
        assertNotNull(payTemplate.getAlipayClient("default"));
        assertNotNull(payTemplate.getAlipayClient("app2"));
        assertNotNull(payTemplate.getAppProperties("default"));
        assertEquals("2021000000000001", payTemplate.getAppProperties("default").getAppId());
    }

    @Test
    @DisplayName("证书模式 certMode: 成功自适应四模加载证书并初始化客户端")
    void testCertMode_initialization() {
        PayProperties payProperties = new PayProperties();
        AlipayPayProperties alipay = payProperties.getAlipay();
        alipay.setDefaultApp("certApp");

        AlipayAppProperties certApp = new AlipayAppProperties();
        certApp.setAppId("2021000000000099");
        certApp.setCertMode(true);
        certApp.setPrivateKey(merchantPrivateKeyPem);
        certApp.setAppCertPath("classpath:certs/test_certificate.pem");
        certApp.setAlipayCertPath("classpath:certs/test_certificate.pem");
        certApp.setAlipayRootCertPath("classpath:certs/test_certificate.pem");
        certApp.setServerUrl(server.url("/gateway.do").toString());
        alipay.getApps().put("certApp", certApp);

        NamedMerchantRegistry registry = new NamedMerchantRegistry(payProperties);
        AlipayTemplate certTemplate = new AlipayTemplate(registry);

        assertNotNull(certTemplate.getAlipayClient("certApp"));
        assertNotNull(certTemplate.getClient("certApp"));
        assertTrue(certTemplate.getAppProperties("certApp").isCertMode());
    }

    @Test
    @DisplayName("公钥模式：公钥带 BEGIN PUBLIC KEY 标头时成功自适应清洗并初始化")
    void testPublicKeyWithPemHeaders_initialization() {
        PayProperties payProperties = new PayProperties();
        AlipayPayProperties alipay = payProperties.getAlipay();
        alipay.setDefaultApp("headerApp");

        AlipayAppProperties headerApp = new AlipayAppProperties();
        headerApp.setAppId("2021000000000088");
        headerApp.setCertMode(false);
        headerApp.setPrivateKey(merchantPrivateKeyPem);
        headerApp.setAlipayPublicKey("-----BEGIN PUBLIC KEY-----\n" + alipayPublicKeyPem + "\n-----END PUBLIC KEY-----");
        headerApp.setServerUrl(server.url("/gateway.do").toString());
        alipay.getApps().put("headerApp", headerApp);

        NamedMerchantRegistry registry = new NamedMerchantRegistry(payProperties);
        AlipayTemplate headerTemplate = new AlipayTemplate(registry);

        assertNotNull(headerTemplate.getClient());
        assertNotNull(headerTemplate.getAlipayClient("headerApp"));
    }

    // =========================================================================
    // 私有辅助工具
    // =========================================================================

    private static String sign(String content, PrivateKey privateKey) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(content.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private static String buildSignedResponse(String responseNodeName, String nodeJson, PrivateKey privateKey) throws Exception {
        String sign = sign(nodeJson, privateKey);
        return "{\"" + responseNodeName + "\":" + nodeJson + ",\"sign\":\"" + sign + "\"}";
    }
}
