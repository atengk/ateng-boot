package io.github.atengk.boot.pay.core.callback;

import com.alipay.api.internal.util.AlipaySignature;
import io.github.atengk.boot.pay.config.properties.AlipayAppProperties;
import io.github.atengk.boot.pay.config.properties.AlipayPayProperties;
import io.github.atengk.boot.pay.config.properties.PayProperties;
import io.github.atengk.boot.pay.config.properties.WechatMerchantProperties;
import io.github.atengk.boot.pay.config.properties.WechatPayProperties;
import io.github.atengk.boot.pay.core.certificate.WechatAutoCertificateManager;
import io.github.atengk.boot.pay.core.lock.InMemoryPayLockManager;
import io.github.atengk.boot.pay.core.merchant.NamedMerchantRegistry;
import io.github.atengk.boot.pay.enums.PayChannel;
import io.github.atengk.boot.pay.enums.PayTradeState;
import io.github.atengk.boot.pay.exception.PaySignException;
import io.github.atengk.boot.pay.model.callback.PayCallbackResponse;
import io.github.atengk.boot.pay.model.callback.PayCallbackResult;
import io.github.atengk.boot.pay.model.callback.PaymentSuccessCallback;
import io.github.atengk.boot.pay.spi.PayCallbackListener;
import io.github.atengk.boot.pay.util.PayKeyLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 支付异步 Webhook 回调网关处理门面 100% 离线自洽单元测试
 * <p>
 * 验证：
 * 1. 微信支付 V3 请求标头验签、AES-GCM 解密与标准领域模型映射；
 * 2. 支付宝表单参数 RSA2 验签与标准领域模型映射；
 * 3. 业务监听器 PayCallbackListener SPI 触发与各种执行结果（SUCCESS, FAIL, IGNORE）；
 * 4. 渠道官方 ACK 报文转译（微信 200/500 JSON，支付宝 200 success/fail）；
 * 5. 双轨并发防重锁锁竞争与重试应答；
 * 6. 篡改报文与签名失败前置阻断拦截。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
class PayCallbackHandlerTest {

    private static final String CLASSPATH_CERT = "classpath:certs/test_certificate.pem";
    private static final String CLASSPATH_KEY = "classpath:certs/test_private_key.pem";
    private static final String API_V3_KEY = "12345678901234567890123456789012"; // 32 字节

    private PrivateKey testPrivateKey;
    private String testPrivateKeyPem;
    private X509Certificate testCert;
    private String testCertSerial;
    private String testPublicKeyPem;

    private InMemoryPayLockManager payLockManager;
    private WechatAutoCertificateManager certificateManager;
    private NamedMerchantRegistry merchantRegistry;
    private PayProperties payProperties;

    private List<PayCallbackListener> listeners;
    private PayCallbackHandler handler;

    @BeforeEach
    void setUp() {
        // 1. 加载测试公私钥与证书
        testPrivateKey = PayKeyLoader.loadPrivateKey(CLASSPATH_KEY);
        testPrivateKeyPem = PayKeyLoader.loadPrivateKeyPem(CLASSPATH_KEY);
        testCert = PayKeyLoader.loadCertificate(CLASSPATH_CERT);
        testCertSerial = PayKeyLoader.getCertificateSerialNumber(testCert);
        testPublicKeyPem = Base64.getEncoder().encodeToString(testCert.getPublicKey().getEncoded());

        // 2. 初始化防重锁与证书管理器
        payLockManager = new InMemoryPayLockManager();
        certificateManager = new WechatAutoCertificateManager(Duration.ofMinutes(10));
        certificateManager.registerCertificates("1900000001", Map.of(testCertSerial, testCert));

        // 3. 配置多商户（微信与支付宝默认商户）
        payProperties = new PayProperties();

        // 微信默认商户
        WechatPayProperties wechat = payProperties.getWechat();
        wechat.setDefaultMerchant("default");
        WechatMerchantProperties defaultMch = new WechatMerchantProperties();
        defaultMch.setMchId("1900000001");
        defaultMch.setAppId("wx_default_app");
        defaultMch.setApiV3Key(API_V3_KEY);
        defaultMch.setMerchantSerialNumber(testCertSerial);
        defaultMch.setPrivateKeyPath(CLASSPATH_KEY);
        wechat.getMerchants().put("default", defaultMch);

        // 支付宝默认应用
        AlipayPayProperties alipay = payProperties.getAlipay();
        alipay.setDefaultApp("default");
        AlipayAppProperties defaultApp = new AlipayAppProperties();
        defaultApp.setAppId("2021000000000001");
        defaultApp.setPrivateKey(testPrivateKeyPem);
        defaultApp.setAlipayPublicKey(testPublicKeyPem);
        defaultApp.setSignType("RSA2");
        defaultApp.setCharset("UTF-8");
        alipay.getApps().put("default", defaultApp);

        merchantRegistry = new NamedMerchantRegistry(payProperties);
        listeners = new ArrayList<>();

        handler = new PayCallbackHandler(merchantRegistry, certificateManager, payLockManager, payProperties, listeners);
    }

    @AfterEach
    void tearDown() {
        if (certificateManager != null) {
            certificateManager.destroy();
        }
        if (payLockManager != null) {
            payLockManager.clear();
        }
    }

    // =========================================================================
    // 1. 微信支付 V3 回调测试
    // =========================================================================

    @Test
    @DisplayName("微信回调: 正常验签解密、监听器触发与 SUCCESS 确认应答")
    void testWechatCallbackSuccess() throws Exception {
        AtomicBoolean listenerInvoked = new AtomicBoolean(false);
        listeners.add(callback -> {
            listenerInvoked.set(true);
            assertEquals(PayChannel.WECHAT_PAY, callback.getChannel());
            assertEquals("WX_ORDER_1001", callback.getOutTradeNo());
            assertEquals("WX_TX_2002", callback.getTransactionId());
            assertEquals(new BigDecimal("99.90"), callback.getAmount());
            assertEquals("mock_openid_abc", callback.getPayerId());
            assertEquals(PayTradeState.SUCCESS, callback.getTradeState());
            assertEquals("custom_attach_data", callback.getAttach());
            return PayCallbackResult.success();
        });

        String transactionJson = buildWechatTransactionJson("WX_ORDER_1001", "WX_TX_2002", 9990, "mock_openid_abc", "custom_attach_data");
        Map<String, String> headers = new HashMap<>();
        String body = buildWechatNotificationBody(transactionJson, headers);

        PayCallbackResponse response = handler.handleWechat("default", headers, body);

        assertTrue(listenerInvoked.get());
        assertNotNull(response);
        assertEquals(200, response.getHttpStatus());
        assertEquals("{\"code\":\"SUCCESS\",\"message\":\"成功\"}", response.getResponseBody());
        assertTrue(response.isSuccess());
        assertNotNull(response.getCallback());
        assertEquals("WX_ORDER_1001", response.getCallback().getOutTradeNo());
    }

    @Test
    @DisplayName("微信回调: 业务监听器返回 IGNORE 静默回复 SUCCESS")
    void testWechatCallbackIgnore() throws Exception {
        listeners.add(callback -> PayCallbackResult.ignore("订单已处理"));

        String transactionJson = buildWechatTransactionJson("WX_ORDER_IGNORE", "WX_TX_IGN", 100, "openid", null);
        Map<String, String> headers = new HashMap<>();
        String body = buildWechatNotificationBody(transactionJson, headers);

        PayCallbackResponse response = handler.handleWechat("default", headers, body);

        assertEquals(200, response.getHttpStatus());
        assertEquals("{\"code\":\"SUCCESS\",\"message\":\"成功\"}", response.getResponseBody());
        assertTrue(response.isIgnore());
    }

    @Test
    @DisplayName("微信回调: 业务监听器返回 FAIL 回复 HTTP 500 触发阶梯重试")
    void testWechatCallbackFail() throws Exception {
        listeners.add(callback -> PayCallbackResult.fail("库存不足记账失败"));

        String transactionJson = buildWechatTransactionJson("WX_ORDER_FAIL", "WX_TX_FAIL", 5000, "openid", null);
        Map<String, String> headers = new HashMap<>();
        String body = buildWechatNotificationBody(transactionJson, headers);

        PayCallbackResponse response = handler.handleWechat("default", headers, body);

        assertEquals(500, response.getHttpStatus());
        assertTrue(response.getResponseBody().contains("库存不足记账失败"));
        assertTrue(response.isFail());
    }

    @Test
    @DisplayName("微信回调: 签名被篡改拦截并抛出 PaySignException / 返回 HTTP 500 FAIL")
    void testWechatCallbackTamperedSignature() throws Exception {
        String transactionJson = buildWechatTransactionJson("WX_ORDER_TAMPER", "WX_TX_TAMPER", 1000, "openid", null);
        Map<String, String> headers = new HashMap<>();
        String body = buildWechatNotificationBody(transactionJson, headers);

        // 故意篡改签名内容
        headers.put("Wechatpay-Signature", Base64.getEncoder().encodeToString("bad_signature".getBytes(StandardCharsets.UTF_8)));

        // 1. handle 模式返回 HTTP 500
        PayCallbackResponse response = handler.handleWechat("default", headers, body);
        assertEquals(500, response.getHttpStatus());
        assertTrue(response.isFail());

        // 2. 编程式 parseAndVerify 抛出 PaySignException
        assertThrows(PaySignException.class, () -> handler.parseAndVerifyWechat("default", headers, body));
    }

    @Test
    @DisplayName("微信回调: 并发防重锁争抢冲突返回 FAIL 重试应答")
    void testWechatCallbackLockContention() throws Exception {
        String outTradeNo = "WX_ORDER_LOCKED";
        String lockKey = "WECHAT_PAY:" + outTradeNo;

        // 预先获取防重锁
        assertTrue(payLockManager.tryLock(lockKey, Duration.ofSeconds(10)));

        String transactionJson = buildWechatTransactionJson(outTradeNo, "WX_TX_LOCKED", 1000, "openid", null);
        Map<String, String> headers = new HashMap<>();
        String body = buildWechatNotificationBody(transactionJson, headers);

        PayCallbackResponse response = handler.handleWechat("default", headers, body);

        // 锁冲突回复 500 FAIL，触发微信下一次重试
        assertEquals(500, response.getHttpStatus());
        assertTrue(response.getResponseBody().contains("并发防重锁冲突"));
        assertTrue(response.isFail());

        // 释放预占锁
        payLockManager.unlock(lockKey);
    }

    // =========================================================================
    // 2. 支付宝回调测试
    // =========================================================================

    @Test
    @DisplayName("支付宝回调: 正常 RSA2 验签、监听器触发与 success 确认应答")
    void testAlipayCallbackSuccess() throws Exception {
        AtomicBoolean listenerInvoked = new AtomicBoolean(false);
        listeners.add(callback -> {
            listenerInvoked.set(true);
            assertEquals(PayChannel.ALIPAY, callback.getChannel());
            assertEquals("ALI_ORDER_2001", callback.getOutTradeNo());
            assertEquals("ALI_TX_3002", callback.getTransactionId());
            assertEquals(new BigDecimal("88.88"), callback.getAmount());
            assertEquals("2088102123456789", callback.getPayerId());
            assertEquals(PayTradeState.SUCCESS, callback.getTradeState());
            assertEquals("passback_ali_data", callback.getAttach());
            return PayCallbackResult.success();
        });

        Map<String, String> params = buildAlipayParams("ALI_ORDER_2001", "ALI_TX_3002", "88.88",
                "2088102123456789", "TRADE_SUCCESS", "passback_ali_data");

        PayCallbackResponse response = handler.handleAlipay("default", params);

        assertTrue(listenerInvoked.get());
        assertNotNull(response);
        assertEquals(200, response.getHttpStatus());
        assertEquals("success", response.getResponseBody());
        assertTrue(response.isSuccess());
        assertNotNull(response.getCallback());
        assertEquals("ALI_ORDER_2001", response.getCallback().getOutTradeNo());
    }

    @Test
    @DisplayName("支付宝回调: 业务监听器返回 IGNORE 静默回复 success")
    void testAlipayCallbackIgnore() throws Exception {
        listeners.add(callback -> PayCallbackResult.ignore("重复处理"));

        Map<String, String> params = buildAlipayParams("ALI_ORDER_IGN", "ALI_TX_IGN", "1.00",
                "2088001", "TRADE_SUCCESS", null);

        PayCallbackResponse response = handler.handleAlipay("default", params);

        assertEquals(200, response.getHttpStatus());
        assertEquals("success", response.getResponseBody());
        assertTrue(response.isIgnore());
    }

    @Test
    @DisplayName("支付宝回调: 业务监听器返回 FAIL 回复 fail 触发官方重试")
    void testAlipayCallbackFail() throws Exception {
        listeners.add(callback -> PayCallbackResult.fail("业务系统维护中"));

        Map<String, String> params = buildAlipayParams("ALI_ORDER_FAIL", "ALI_TX_FAIL", "50.00",
                "2088002", "TRADE_SUCCESS", null);

        PayCallbackResponse response = handler.handleAlipay("default", params);

        assertEquals(200, response.getHttpStatus());
        assertEquals("fail", response.getResponseBody());
        assertTrue(response.isFail());
    }

    @Test
    @DisplayName("支付宝回调: 篡改参数导致验签失败返回 fail")
    void testAlipayCallbackTamperedParams() throws Exception {
        Map<String, String> params = buildAlipayParams("ALI_ORDER_TAMPER", "ALI_TX_TAMPER", "100.00",
                "2088003", "TRADE_SUCCESS", null);

        // 篡改金额
        params.put("total_amount", "999999.00");

        // 1. handle 模式回复 fail
        PayCallbackResponse response = handler.handleAlipay("default", params);
        assertEquals(200, response.getHttpStatus());
        assertEquals("fail", response.getResponseBody());
        assertTrue(response.isFail());

        // 2. parseAndVerify 抛出 PaySignException
        assertThrows(PaySignException.class, () -> handler.parseAndVerifyAlipay("default", params));
    }

    @Test
    @DisplayName("支付宝回调: 并发防重锁争抢冲突回复 fail")
    void testAlipayCallbackLockContention() throws Exception {
        String outTradeNo = "ALI_ORDER_LOCKED";
        String lockKey = "ALIPAY:" + outTradeNo;

        // 预先占有锁
        assertTrue(payLockManager.tryLock(lockKey, Duration.ofSeconds(10)));

        Map<String, String> params = buildAlipayParams(outTradeNo, "ALI_TX_LOCK", "20.00",
                "2088004", "TRADE_SUCCESS", null);

        PayCallbackResponse response = handler.handleAlipay("default", params);

        assertEquals(200, response.getHttpStatus());
        assertEquals("fail", response.getResponseBody());
        assertTrue(response.isFail());

        payLockManager.unlock(lockKey);
    }

    // =========================================================================
    // 辅助工具方法
    // =========================================================================

    private String buildWechatTransactionJson(String outTradeNo, String txId, int totalFen, String openid, String attach) {
        return "{\n" +
                "  \"appid\": \"wx_default_app\",\n" +
                "  \"mchid\": \"1900000001\",\n" +
                "  \"out_trade_no\": \"" + outTradeNo + "\",\n" +
                "  \"transaction_id\": \"" + txId + "\",\n" +
                "  \"trade_type\": \"NATIVE\",\n" +
                "  \"trade_state\": \"SUCCESS\",\n" +
                "  \"trade_state_desc\": \"支付成功\",\n" +
                "  \"bank_type\": \"CMC\",\n" +
                (attach != null ? "  \"attach\": \"" + attach + "\",\n" : "") +
                "  \"success_time\": \"2026-10-23T22:30:00+08:00\",\n" +
                "  \"amount\": {\n" +
                "    \"total\": " + totalFen + ",\n" +
                "    \"payer_total\": " + totalFen + ",\n" +
                "    \"currency\": \"CNY\",\n" +
                "    \"payer_currency\": \"CNY\"\n" +
                "  },\n" +
                "  \"payer\": {\n" +
                "    \"openid\": \"" + openid + "\"\n" +
                "  }\n" +
                "}";
    }

    private String buildWechatNotificationBody(String transactionJson, Map<String, String> headers) throws Exception {
        String nonce = "aes_gcm_nonce_" + System.currentTimeMillis();
        String associatedData = "transaction";

        // AES-256-GCM 加密
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec keySpec = new SecretKeySpec(API_V3_KEY.getBytes(StandardCharsets.UTF_8), "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8));
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
        cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertextBytes = cipher.doFinal(transactionJson.getBytes(StandardCharsets.UTF_8));
        String ciphertextBase64 = Base64.getEncoder().encodeToString(ciphertextBytes);

        String body = "{\n" +
                "  \"id\": \"EV-" + System.currentTimeMillis() + "\",\n" +
                "  \"create_time\": \"2026-10-23T22:30:00+08:00\",\n" +
                "  \"resource_type\": \"encrypt-resource\",\n" +
                "  \"event_type\": \"TRANSACTION.SUCCESS\",\n" +
                "  \"summary\": \"支付成功\",\n" +
                "  \"resource\": {\n" +
                "    \"algorithm\": \"AEAD_AES_256_GCM\",\n" +
                "    \"ciphertext\": \"" + ciphertextBase64 + "\",\n" +
                "    \"associated_data\": \"" + associatedData + "\",\n" +
                "    \"original_type\": \"transaction\",\n" +
                "    \"nonce\": \"" + nonce + "\"\n" +
                "  }\n" +
                "}";

        long timestamp = Instant.now().getEpochSecond();
        String headerNonce = "header_nonce_" + System.currentTimeMillis();
        String signPayload = timestamp + "\n" + headerNonce + "\n" + body + "\n";

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(testPrivateKey);
        signer.update(signPayload.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(signer.sign());

        headers.put("Wechatpay-Timestamp", String.valueOf(timestamp));
        headers.put("Wechatpay-Nonce", headerNonce);
        headers.put("Wechatpay-Signature", signature);
        headers.put("Wechatpay-Serial", testCertSerial);
        headers.put("Wechatpay-Signature-Type", "WECHATPAY2-SHA256-RSA2048");

        return body;
    }

    private Map<String, String> buildAlipayParams(String outTradeNo, String tradeNo, String totalAmount,
                                                  String buyerId, String tradeStatus, String passbackParams) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("app_id", "2021000000000001");
        params.put("out_trade_no", outTradeNo);
        params.put("trade_no", tradeNo);
        params.put("total_amount", totalAmount);
        params.put("buyer_id", buyerId);
        params.put("trade_status", tradeStatus);
        params.put("gmt_payment", "2026-10-23 22:30:00");
        params.put("notify_time", "2026-10-23 22:30:01");
        params.put("charset", "UTF-8");
        params.put("sign_type", "RSA2");
        if (passbackParams != null) {
            params.put("passback_params", passbackParams);
        }

        String content = AlipaySignature.getSignCheckContentV1(params);
        String sign = AlipaySignature.rsaSign(content, testPrivateKeyPem, "UTF-8", "RSA2");
        params.put("sign", sign);
        return params;
    }
}
