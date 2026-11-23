package io.github.atengk.boot.sample.pay;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.pay.config.properties.AlipayAppProperties;
import io.github.atengk.boot.pay.core.AlipayTemplate;
import io.github.atengk.boot.pay.core.PayTemplate;
import io.github.atengk.boot.pay.core.WechatPayTemplate;
import io.github.atengk.boot.pay.core.certificate.WechatAutoCertificateManager;
import io.github.atengk.boot.pay.core.executor.BarcodePayExecutor;
import io.github.atengk.boot.pay.core.merchant.NamedMerchantRegistry;
import io.github.atengk.boot.pay.core.profitsharing.WechatProfitSharingService;
import io.github.atengk.boot.pay.enums.PayChannel;
import io.github.atengk.boot.pay.enums.PayTradeState;
import io.github.atengk.boot.pay.enums.PayTradeType;
import io.github.atengk.boot.pay.enums.PayTransferState;
import io.github.atengk.boot.pay.model.callback.OrderClosedCallback;
import io.github.atengk.boot.pay.model.callback.PayCallbackResult;
import io.github.atengk.boot.pay.model.callback.PaymentSuccessCallback;
import io.github.atengk.boot.pay.model.callback.RefundSuccessCallback;
import io.github.atengk.boot.pay.model.callback.TransferNotifyCallback;
import io.github.atengk.boot.pay.util.PayKeyLoader;
import io.github.atengk.boot.sample.pay.dto.*;
import io.github.atengk.boot.sample.pay.listener.OrderPaymentCallbackListener;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 支付场景端到端真实集成测试 (双模自适应 + MockWebServer 网关驱动)
 * <p>
 * 遵循 ADR-0028 与 ADR-0077 规范：
 * 1. 彻底移除所有 {@code @MockBean} 假桩，让真实自动装配的 {@link PayTemplate}、{@link WechatPayTemplate}、
 *    {@link AlipayTemplate}、{@link BarcodePayExecutor} 与 {@link WechatProfitSharingService} 全真执行；
 * 2. 基于纯内存 {@link MockWebServer} 模拟微信支付 V3 与支付宝官方开放平台网关，全真跑通协议握手、加签与验签；
 * 3. 覆盖 Native 扫码、JSAPI/小程序 (微信+支付宝)、H5 网页、App 调起、订单状态查询、关单与超时自动撤销冲正；
 * 4. 覆盖资金售后退款、单笔转账、财务对账单流式解压解析与微信资金分账。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("多渠道支付场景端到端集成测试 (真实全真双模驱动)")
class PaySampleIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderPaymentCallbackListener callbackListener;

    @Autowired
    private PayTemplate payTemplate;

    @Autowired
    private WechatPayTemplate wechatPayTemplate;

    @Autowired
    private AlipayTemplate alipayTemplate;

    @Autowired
    private BarcodePayExecutor barcodePayExecutor;

    @Autowired
    private WechatProfitSharingService profitSharingService;

    @Autowired
    private NamedMerchantRegistry namedMerchantRegistry;

    @Autowired
    private WechatAutoCertificateManager certificateManager;

    @Autowired
    private Environment environment;

    private boolean isLocalProfile() {
        return environment != null && Arrays.asList(environment.getActiveProfiles()).contains("local");
    }

    private static MockWebServer server;
    private static PrivateKey rsaPrivateKey;
    private static byte[] gzipBillBytes;
    private static String gzipBillSha1;
    private static byte[] alipayDualCsvZipBytes;
    private static PrivateKey alipayPrivateKey;
    private static String alipayPublicKeyPem;

    private static final String MOCK_WECHAT_BILL_CSV = """
            交易时间,公众账号ID,商户号,特约商户号,设备号,微信订单号,商户订单号,用户标识,交易类型,交易状态,付款银行,货币种类,应结订单金额,代金券金额,微信退款单号,商户退款单号,退款金额,充值券退款金额,退款类型,退款状态,商品名称,商户数据包,手续费,费率,订单金额,申请退款金额,费率备注
            `2026-10-24 09:15:00,`wx8888888888888888,`1900000109,`0,`, `4200000000202610240000000001,`WX_OUT_001,`oUpF8uMuAJO_M2pxb1Q9zNjWeS6o,`JSAPI,`SUCCESS,`CMB_CREDIT,`CNY,`100.00,`0.00,`0,`0,`0.00,`0.00,`,`, `测试商品A,`, `0.60,`0.60%,`100.00,`0.00,`
            `2026-10-24 10:30:00,`wx8888888888888888,`1900000109,`0,`, `4200000000202610240000000002,`WX_OUT_002,`oUpF8uMuAJO_M2pxb1Q9zNjWeS6o,`NATIVE,`SUCCESS,`ICBC_DEBIT,`CNY,`50.00,`0.00,`0,`0,`0.00,`0.00,`,`, `测试商品B,`, `0.30,`0.60%,`50.00,`0.00,`
            总交易单数,应结订单总金额,退款总金额,充值券退款总金额,手续费总金额,订单总金额,申请退款总金额
            `2,`150.00,`0.00,`0.00,`0.90,`150.00,`0.00
            """;

    private static final String MOCK_ALIPAY_DETAIL_CSV = String.join("\n",
            "#支付宝业务明细查询",
            "#账号：2088000000000000",
            "支付宝交易号,商户订单号,业务类型,商品名称,创建时间,完成时间,门店编号,门店名称,操作员,终端号,对方账户,订单金额（元）,商家实收（元）,支付宝红包（元）,集分宝（元）,支付宝优惠（元）,商家优惠（元）,券核销金额（元）,券名称,商家红包消费金额（元）,卡消费金额（元）,退款批次号/请求号,服务费（元）,分润（元）,备注",
            "202611182200100001,ALI_OUT_001,交易,会员套餐A,2026-11-18 09:00:00,2026-11-18 09:00:05,,,, ,buyer1@example.com,80.00,79.50,0.00,0.00,0.00,0.00,0.00,,0.00,0.00,,0.50,0.00,",
            "202611182200100002,ALI_OUT_002,交易,会员套餐B,2026-11-18 10:00:00,2026-11-18 10:00:05,,,, ,buyer2@example.com,40.00,39.80,0.00,0.00,0.00,0.00,0.00,,0.00,0.00,,0.20,0.00,");

    private static final String MOCK_ALIPAY_SUMMARY_CSV = String.join("\n",
            "#支付宝业务汇总查询",
            "总交易笔数,总交易金额（元）,总商家实收（元）,总退款金额（元）,总服务费（元）",
            "2,120.00,119.30,0.00,0.70");

    @BeforeAll
    static void initServerAndKeys() throws Exception {
        rsaPrivateKey = PayKeyLoader.loadPrivateKey(PayKeyLoader.loadString("classpath:certs/test_private_key.pem"));

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair alipayKeyPair = kpg.generateKeyPair();
        alipayPrivateKey = alipayKeyPair.getPrivate();
        alipayPublicKeyPem = Base64.getEncoder().encodeToString(alipayKeyPair.getPublic().getEncoded());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(MOCK_WECHAT_BILL_CSV.getBytes(StandardCharsets.UTF_8));
        }
        gzipBillBytes = baos.toByteArray();

        Charset gbk = Charset.forName("GBK");
        ByteArrayOutputStream zipBaos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(zipBaos, gbk)) {
            zos.putNextEntry(new ZipEntry("2088000000000000_20261118_业务明细(汇总).csv"));
            zos.write(MOCK_ALIPAY_SUMMARY_CSV.getBytes(gbk));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("2088000000000000_20261118_业务明细.csv"));
            zos.write(MOCK_ALIPAY_DETAIL_CSV.getBytes(gbk));
            zos.closeEntry();
        }
        alipayDualCsvZipBytes = zipBaos.toByteArray();

        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] hash = md.digest(gzipBillBytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        gzipBillSha1 = sb.toString();

        server = new MockWebServer();
        server.start();
    }

    @AfterAll
    static void tearDownServer() throws Exception {
        if (server != null) {
            server.shutdown();
        }
    }

    @BeforeEach
    void setUpRealTemplates() throws Exception {
        if (isLocalProfile()) {
            return;
        }
        server.setDispatcher(createDispatcher());

        // 1. 将微信官方域名网络请求重定向至本地 MockWebServer
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

        com.wechat.pay.java.core.http.HttpClient customHttpClient = new com.wechat.pay.java.core.http.DefaultHttpClientBuilder()
                .config(wechatPayTemplate.getWechatConfig())
                .okHttpClient(redirectOkHttpClient)
                .build();

        wechatPayTemplate.setCustomHttpClient(customHttpClient);

        // 2. 将支付宝默认应用网关 URL 与验签公钥动态指向本地 MockWebServer
        AlipayAppProperties defaultApp = namedMerchantRegistry.resolveAlipayApp("default");
        defaultApp.setServerUrl(server.url("/gateway.do").toString());
        defaultApp.setAlipayPublicKey(alipayPublicKeyPem);
        alipayTemplate.clearClientCache();
    }

    private final List<String> capturedOutboundRequests = new CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanContext() {
        UserContextHolder.clear();
        MDC.clear();
        capturedOutboundRequests.clear();
        if (callbackListener != null) {
            callbackListener.reset();
        }
    }

    private static MockResponse mockWechatResponse(int statusCode, String body) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        String nonce = "mock_nonce_" + System.currentTimeMillis();
        String signPayload = timestamp + "\n" + nonce + "\n" + (body != null ? body : "") + "\n";

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(rsaPrivateKey);
        signer.update(signPayload.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(signer.sign());

        MockResponse response = new MockResponse()
                .setResponseCode(statusCode)
                .setHeader("Content-Type", "application/json")
                .setHeader("Wechatpay-Timestamp", String.valueOf(timestamp))
                .setHeader("Wechatpay-Nonce", nonce)
                .setHeader("Wechatpay-Signature", signature)
                .setHeader("Wechatpay-Serial", "PUB_KEY_ID_TEST_001");

        if (body != null) {
            response.setBody(body);
        }
        return response;
    }

    private static MockResponse buildAlipayMockResponse(String responseNode, String contentJson) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(alipayPrivateKey);
        signature.update(contentJson.getBytes(StandardCharsets.UTF_8));
        String sign = Base64.getEncoder().encodeToString(signature.sign());
        String body = "{\"" + responseNode + "\":" + contentJson + ",\"sign\":\"" + sign + "\"}";
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(body);
    }

    private Dispatcher createDispatcher() {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() != null ? request.getPath() : "";
                String method = request.getMethod() != null ? request.getMethod() : "GET";
                String rawBody = request.getBody().clone().readUtf8();
                String snapshotBody = path.startsWith("/gateway.do")
                        ? URLDecoder.decode(rawBody, StandardCharsets.UTF_8)
                        : rawBody;
                String serialHeader = request.getHeader("Wechatpay-Serial");
                capturedOutboundRequests.add(method + " " + path + " | serial=" + serialHeader + " | body=" + snapshotBody);

                try {
                    // 1. 微信支付路由
                    if (path.startsWith("/v3/pay/transactions/native")) {
                        return mockWechatResponse(200, "{\"code_url\":\"weixin://wxpay/bizpayurl?pr=mockQrCode999\"}");
                    }
                    if (path.startsWith("/v3/pay/transactions/jsapi")) {
                        return mockWechatResponse(200, "{\"prepay_id\":\"wx20261023prepay001\"}");
                    }
                    if (path.startsWith("/v3/pay/transactions/h5")) {
                        return mockWechatResponse(200, "{\"h5_url\":\"https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?prepay_id=mockPrepay003\"}");
                    }
                    if (path.startsWith("/v3/pay/transactions/app")) {
                        return mockWechatResponse(200, "{\"prepay_id\":\"wx20261023prepay004\"}");
                    }
                    if (path.startsWith("/v3/pay/transactions/codepay")) {
                        if (rawBody.contains("REVERSE")) {
                            return mockWechatResponse(200, "{\"trade_state\":\"USERPAYING\",\"trade_state_desc\":\"等待用户输入密码\",\"transaction_id\":\"TRANSACTION_20261023_001\"}");
                        }
                        return mockWechatResponse(200, "{\"trade_state\":\"SUCCESS\",\"trade_state_desc\":\"支付成功\",\"transaction_id\":\"TRANSACTION_20261023_001\"}");
                    }
                    if (path.contains("/reverse")) {
                        return mockWechatResponse(200, "{\"result_code\":\"SUCCESS\"}");
                    }
                    if (path.contains("/close")) {
                        return mockWechatResponse(204, null);
                    }
                    if (path.startsWith("/v3/pay/transactions/out-trade-no/") || path.startsWith("/v3/pay/transactions/id/")) {
                        if (path.contains("REVERSE")) {
                            return mockWechatResponse(200, "{\"trade_state\":\"USERPAYING\",\"trade_state_desc\":\"等待用户输入密码\",\"transaction_id\":\"TRANSACTION_20261023_001\",\"amount\":{\"total\":6600,\"payer_total\":6600,\"currency\":\"CNY\"}}");
                        }
                        return mockWechatResponse(200, "{\"trade_state\":\"SUCCESS\",\"trade_state_desc\":\"支付成功\",\"transaction_id\":\"TRANSACTION_20261023_001\",\"amount\":{\"total\":9990,\"payer_total\":9990,\"currency\":\"CNY\"}}");
                    }
                    if (path.startsWith("/v3/refund/domestic/refunds") && "POST".equalsIgnoreCase(method)) {
                        return mockWechatResponse(200, "{\"refund_id\":\"REFUND_TX_20261024_001\",\"out_refund_no\":\"REF_20261024_012\",\"status\":\"PROCESSING\",\"amount\":{\"total\":10000,\"refund\":2000,\"payer_total\":10000,\"payer_refund\":2000,\"currency\":\"CNY\"}}");
                    }
                    if (path.startsWith("/v3/refund/domestic/refunds/") && "GET".equalsIgnoreCase(method)) {
                        return mockWechatResponse(200, "{\"refund_id\":\"REFUND_TX_20261024_001\",\"out_refund_no\":\"REF_20261024_012\",\"status\":\"SUCCESS\",\"amount\":{\"total\":10000,\"refund\":2000,\"payer_total\":10000,\"payer_refund\":2000,\"currency\":\"CNY\"}}");
                    }
                    if (path.matches("^/v3/fund-app/mch-transfer/transfer-bills/out-bill-no/[^/]+/cancel$")
                            && "POST".equalsIgnoreCase(method)) {
                        return mockWechatResponse(200, "{\"out_bill_no\":\"MCH_SAMPLE_TRANSFER_024\",\"transfer_bill_no\":\"133000007110109690030000024\",\"state\":\"CANCELLED\",\"update_time\":\"2026-11-18T15:03:00+08:00\"}");
                    }
                    if (path.startsWith("/v3/fund-app/mch-transfer/transfer-bills") && "POST".equalsIgnoreCase(method)) {
                        if (rawBody.contains("MCH_SAMPLE_TRANSFER_024")) {
                            return mockWechatResponse(200, "{\"out_bill_no\":\"MCH_SAMPLE_TRANSFER_024\",\"transfer_bill_no\":\"133000007110109690030000024\",\"state\":\"WAIT_USER_CONFIRM\",\"package_info\":\"MOCK_PACKAGE_INFO_024\"}");
                        }
                        return mockWechatResponse(200, "{\"out_bill_no\":\"TRANS_BIZ_20261024_015\",\"transfer_bill_no\":\"TRANSFER_TX_20261024_001\",\"state\":\"SUCCESS\",\"package_info\":\"MOCK_PACKAGE_INFO\"}");
                    }
                    if (path.startsWith("/v3/fund-app/mch-transfer/transfer-bills/out-bill-no/") && "GET".equalsIgnoreCase(method)) {
                        if (path.contains("MCH_SAMPLE_TRANSFER_024")) {
                            return mockWechatResponse(200, "{\"out_bill_no\":\"MCH_SAMPLE_TRANSFER_024\",\"transfer_bill_no\":\"133000007110109690030000024\",\"state\":\"WAIT_USER_CONFIRM\",\"transfer_amount\":20000}");
                        }
                        return mockWechatResponse(200, "{\"out_bill_no\":\"TRANS_BIZ_20261024_015\",\"transfer_bill_no\":\"TRANSFER_TX_20261024_001\",\"state\":\"SUCCESS\"}");
                    }
                    if (path.startsWith("/v3/bill/tradebill")) {
                        String resp = "{\"download_url\":\"http://" + server.getHostName() + ":" + server.getPort()
                                + "/mock-bill-download\",\"hash_type\":\"SHA1\",\"hash_value\":\"" + gzipBillSha1 + "\"}";
                        return mockWechatResponse(200, resp);
                    }
                    if (path.startsWith("/v3/profitsharing/receivers/add")) {
                        return mockWechatResponse(200, "{\"type\":\"PERSONAL_OPENID\",\"account\":\"oUpF8uMuAJO_M2pxb1Q9zNjWeS6o\",\"relation_type\":\"USER\"}");
                    }
                    if (path.startsWith("/v3/profitsharing/receivers/delete")) {
                        return mockWechatResponse(200, "{\"type\":\"PERSONAL_OPENID\",\"account\":\"oUpF8uMuAJO_M2pxb1Q9zNjWeS6o\"}");
                    }
                    if (path.startsWith("/v3/profitsharing/orders/unfreeze")) {
                        return mockWechatResponse(200, "{\"out_order_no\":\"ORD_UNFREEZE_001\",\"order_id\":\"3000002000202610240001\",\"state\":\"FINISHED\"}");
                    }
                    if (path.startsWith("/v3/profitsharing/orders") && "POST".equalsIgnoreCase(method)) {
                        return mockWechatResponse(200, "{\"transaction_id\":\"TRANSACTION_20261118_025\",\"out_order_no\":\"PS_SAMPLE_025\",\"order_id\":\"3000002000202610240001\",\"state\":\"FINISHED\"}");
                    }
                    if (path.startsWith("/v3/profitsharing/orders/") && "GET".equalsIgnoreCase(method)) {
                        return mockWechatResponse(200, "{\"transaction_id\":\"TRANSACTION_20261118_025\",\"out_order_no\":\"PS_SAMPLE_025\",\"order_id\":\"3000002000202610240001\",\"state\":\"FINISHED\"}");
                    }
                    if (path.startsWith("/v3/profitsharing/return-orders") && "POST".equalsIgnoreCase(method)) {
                        return mockWechatResponse(200, "{\"order_id\":\"3000002000202610240001\",\"out_order_no\":\"PS_SAMPLE_025\",\"out_return_no\":\"RET_SAMPLE_025\",\"return_mchid\":\"1900000002\",\"amount\":100,\"result\":\"SUCCESS\"}");
                    }
                    if (path.startsWith("/v3/profitsharing/return-orders/") && "GET".equalsIgnoreCase(method)) {
                        return mockWechatResponse(200, "{\"order_id\":\"3000002000202610240001\",\"out_order_no\":\"PS_SAMPLE_025\",\"out_return_no\":\"RET_SAMPLE_025\",\"return_mchid\":\"1900000002\",\"amount\":100,\"result\":\"SUCCESS\"}");
                    }
                    if (path.startsWith("/v3/profitsharing/transactions/") && path.contains("/amounts")) {
                        return mockWechatResponse(200, "{\"transaction_id\":\"TRANSACTION_20261118_025\",\"unsplit_amount\":500}");
                    }
                    if (path.startsWith("/v3/profitsharing/merchant-configs/")) {
                        return mockWechatResponse(200, "{\"sub_mchid\":\"1900000002\",\"max_ratio\":3000}");
                    }

                    // 2. 账单下载流
                    if (path.startsWith("/mock-alipay-bill-zip")) {
                        return new MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "application/zip")
                                .setBody(new okio.Buffer().write(alipayDualCsvZipBytes));
                    }
                    if (path.startsWith("/mock-bill-download")) {
                        return new MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "application/octet-stream")
                                .setBody(new okio.Buffer().write(gzipBillBytes));
                    }

                    // 3. 支付宝网关路由
                    if (path.startsWith("/gateway.do")) {
                        String decodedBody = snapshotBody;
                        String decodedPath = URLDecoder.decode(path, StandardCharsets.UTF_8);

                        if (decodedPath.contains("alipay.trade.create") || decodedBody.contains("alipay.trade.create")) {
                            String node = "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"202610232200140099\",\"out_trade_no\":\"ORD_ALI_MINI_001\"}";
                            return buildAlipayMockResponse("alipay_trade_create_response", node);
                        }
                        if (decodedPath.contains("alipay.trade.precreate") || decodedBody.contains("alipay.trade.precreate")) {
                            String node = "{\"code\":\"10000\",\"msg\":\"Success\",\"qr_code\":\"https://qr.alipay.com/bax012345678\"}";
                            return buildAlipayMockResponse("alipay_trade_precreate_response", node);
                        }
                        if (decodedPath.contains("alipay.trade.pay") || decodedBody.contains("alipay.trade.pay")) {
                            if (decodedBody.contains("USERPAYING") || decodedBody.contains("REVERSE")) {
                                String node = "{\"code\":\"10003\",\"msg\":\"Waiting for user to pay\",\"trade_no\":\"202610232200140088\",\"out_trade_no\":\"BARCODE_ORDER_1001\"}";
                                return buildAlipayMockResponse("alipay_trade_pay_response", node);
                            }
                            String node = "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"202610232200140088\",\"out_trade_no\":\"BARCODE_ORDER_1001\"}";
                            return buildAlipayMockResponse("alipay_trade_pay_response", node);
                        }
                        if (decodedPath.contains("alipay.trade.query") || decodedBody.contains("alipay.trade.query")) {
                            if (decodedBody.contains("REVERSE")) {
                                String node = "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"202610232200140088\",\"out_trade_no\":\"BARCODE_ORDER_1001\",\"trade_status\":\"WAIT_BUYER_PAY\",\"total_amount\":\"66.00\"}";
                                return buildAlipayMockResponse("alipay_trade_query_response", node);
                            }
                            String node = "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"202610232200140088\",\"out_trade_no\":\"QUERY_1001\",\"trade_status\":\"TRADE_SUCCESS\",\"total_amount\":\"99.90\"}";
                            return buildAlipayMockResponse("alipay_trade_query_response", node);
                        }
                        if (decodedPath.contains("alipay.trade.close") || decodedBody.contains("alipay.trade.close")) {
                            String node = "{\"code\":\"10000\",\"msg\":\"Success\"}";
                            return buildAlipayMockResponse("alipay_trade_close_response", node);
                        }
                        if (decodedPath.contains("alipay.trade.cancel") || decodedBody.contains("alipay.trade.cancel")) {
                            String node = "{\"code\":\"10000\",\"msg\":\"Success\",\"action\":\"close\",\"out_trade_no\":\"ORD_CANCEL_ALI_023\"}";
                            return buildAlipayMockResponse("alipay_trade_cancel_response", node);
                        }
                        if (decodedPath.contains("alipay.trade.refund") || decodedBody.contains("alipay.trade.refund")) {
                            String node = "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"202610232200140088\",\"refund_fee\":\"20.00\"}";
                            return buildAlipayMockResponse("alipay_trade_refund_response", node);
                        }
                        if (decodedPath.contains("alipay.trade.fastpay.refund.query") || decodedBody.contains("alipay.trade.fastpay.refund.query")) {
                            String node = "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"202610232200140088\",\"refund_amount\":\"20.00\"}";
                            return buildAlipayMockResponse("alipay_trade_fastpay_refund_query_response", node);
                        }
                        if (decodedPath.contains("alipay.fund.trans.uni.transfer") || decodedBody.contains("alipay.fund.trans.uni.transfer")) {
                            String node = "{\"code\":\"10000\",\"msg\":\"Success\",\"out_biz_no\":\"TRANS_BIZ_20261024_015\",\"order_id\":\"TRANSFER_TX_20261024_001\",\"status\":\"SUCCESS\"}";
                            return buildAlipayMockResponse("alipay_fund_trans_uni_transfer_response", node);
                        }
                        if (decodedPath.contains("alipay.fund.trans.common.query") || decodedBody.contains("alipay.fund.trans.common.query")) {
                            String node = "{\"code\":\"10000\",\"msg\":\"Success\",\"out_biz_no\":\"TRANS_BIZ_20261024_015\",\"order_id\":\"TRANSFER_TX_20261024_001\",\"status\":\"SUCCESS\"}";
                            return buildAlipayMockResponse("alipay_fund_trans_common_query_response", node);
                        }
                        if (decodedPath.contains("alipay.data.dataservice.bill.downloadurl.query") || decodedBody.contains("alipay.data.dataservice.bill.downloadurl.query")) {
                            String billPath = decodedBody.contains("2026-11-18") || decodedPath.contains("2026-11-18")
                                    ? "/mock-alipay-bill-zip"
                                    : "/mock-bill-download";
                            String node = "{\"code\":\"10000\",\"msg\":\"Success\",\"bill_download_url\":\"http://" + server.getHostName() + ":" + server.getPort() + billPath + "\"}";
                            return buildAlipayMockResponse("alipay_data_dataservice_bill_downloadurl_query_response", node);
                        }
                        if (decodedPath.contains("alipay.trade.royalty.relation.bind") || decodedBody.contains("alipay.trade.royalty.relation.bind")) {
                            String node = "{\"code\":\"10000\",\"msg\":\"Success\",\"result_code\":\"SUCCESS\"}";
                            return buildAlipayMockResponse("alipay_trade_royalty_relation_bind_response", node);
                        }
                        if (decodedPath.contains("alipay.trade.royalty.relation.unbind") || decodedBody.contains("alipay.trade.royalty.relation.unbind")) {
                            String node = "{\"code\":\"10000\",\"msg\":\"Success\",\"result_code\":\"SUCCESS\"}";
                            return buildAlipayMockResponse("alipay_trade_royalty_relation_unbind_response", node);
                        }
                        if (decodedPath.contains("alipay.trade.royalty.relation.batchquery") || decodedBody.contains("alipay.trade.royalty.relation.batchquery")) {
                            String node = "{\"code\":\"10000\",\"msg\":\"Success\",\"result_code\":\"SUCCESS\",\"current_page_num\":1,\"current_page_size\":20,\"total_page_num\":1,\"total_record_num\":1,\"receiver_list\":[{\"type\":\"userId\",\"account\":\"2088101126708402\",\"name\":\"合作方A\"}]}";
                            return buildAlipayMockResponse("alipay_trade_royalty_relation_batchquery_response", node);
                        }
                        if (decodedPath.contains("alipay.trade.settle.confirm") || decodedBody.contains("alipay.trade.settle.confirm")) {
                            String node = "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"202611182200140026\",\"out_request_no\":\"ALI_CONFIRM_026\",\"settle_amount\":\"99.00\"}";
                            return buildAlipayMockResponse("alipay_trade_settle_confirm_response", node);
                        }
                        if (decodedPath.contains("alipay.trade.order.settle.query") || decodedBody.contains("alipay.trade.order.settle.query")) {
                            String node = "{\"code\":\"10000\",\"msg\":\"Success\",\"out_request_no\":\"ALI_SETTLE_026\",\"royalty_detail_list\":[{\"amount\":\"19.80\",\"state\":\"SUCCESS\"}]}";
                            return buildAlipayMockResponse("alipay_trade_order_settle_query_response", node);
                        }
                        if (decodedPath.contains("alipay.trade.order.settle") || decodedBody.contains("alipay.trade.order.settle")) {
                            String node = "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"202611182200140026\",\"settle_no\":\"20261118SETTLE026\"}";
                            return buildAlipayMockResponse("alipay_trade_order_settle_response", node);
                        }
                    }

                    return new MockResponse().setResponseCode(404).setBody("Not Found: " + path);
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500).setBody("Internal Server Error: " + e.getMessage());
                }
            }
        };
    }

    @Test
    @DisplayName("1. Native 扫码收单：成功生成二维码内容链接与标准 Result 契约")
    void shouldReturnQrCodeForNativeCheckout() throws Exception {
        PayCheckoutDTO dto = PayCheckoutDTO.builder()
                .channel(PayChannel.WECHAT_PAY)
                .outTradeNo("ORD_NATIVE_20261023_001")
                .amount(new BigDecimal("99.90"))
                .subject("云端会员年度订阅")
                .build();

        mockMvc.perform(post("/sample/pay/checkout/native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.channel").value("WECHAT_PAY"))
                .andExpect(jsonPath("$.data.tradeType").value("NATIVE"))
                .andExpect(jsonPath("$.data.outTradeNo").value("ORD_NATIVE_20261023_001"))
                .andExpect(jsonPath("$.data.qrCode").value("weixin://wxpay/bizpayurl?pr=mockQrCode999"));
    }

    @Test
    @DisplayName("2. JSAPI 调起收单：全自动生成二次加签 5 元素调起凭据包 (真实微信 RSA 计算)")
    void shouldReturnWechatJsapiParamsForJsapiCheckout() throws Exception {
        PayCheckoutDTO dto = PayCheckoutDTO.builder()
                .channel(PayChannel.WECHAT_PAY)
                .outTradeNo("ORD_JSAPI_20261023_002")
                .amount(new BigDecimal("12.50"))
                .subject("咖啡订单在线支付")
                .openid("oUpF8uMuAJO_M2pxb1Q9zNjWeS6o")
                .build();

        mockMvc.perform(post("/sample/pay/checkout/jsapi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tradeType").value("JSAPI"))
                .andExpect(jsonPath("$.data.wechatJsapi.appId").value("wx8888888888888888"))
                .andExpect(jsonPath("$.data.wechatJsapi.timeStamp").value(notNullValue()))
                .andExpect(jsonPath("$.data.wechatJsapi.nonceStr").value(notNullValue()))
                .andExpect(jsonPath("$.data.wechatJsapi.package").value("prepay_id=wx20261023prepay001"))
                .andExpect(jsonPath("$.data.wechatJsapi.signType").value("RSA"))
                .andExpect(jsonPath("$.data.wechatJsapi.paySign").value(notNullValue()));
    }

    @Test
    @DisplayName("3. H5 移动网页收单：成功返回跳转 payUrl")
    void shouldReturnPayUrlForH5Checkout() throws Exception {
        PayCheckoutDTO dto = PayCheckoutDTO.builder()
                .channel(PayChannel.WECHAT_PAY)
                .outTradeNo("ORD_H5_20261023_003")
                .amount(new BigDecimal("66.00"))
                .subject("H5网页充值")
                .build();

        mockMvc.perform(post("/sample/pay/checkout/h5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tradeType").value("H5"))
                .andExpect(jsonPath("$.data.payUrl").value("https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?prepay_id=mockPrepay003"));
    }

    @Test
    @DisplayName("4. App 移动收单：成功返回真实加签 orderString")
    void shouldReturnOrderStringForAppCheckout() throws Exception {
        PayCheckoutDTO dto = PayCheckoutDTO.builder()
                .channel(PayChannel.ALIPAY)
                .outTradeNo("ORD_APP_20261023_004")
                .amount(new BigDecimal("199.00"))
                .subject("App应用内消费")
                .build();

        mockMvc.perform(post("/sample/pay/checkout/app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tradeType").value("APP"))
                .andExpect(jsonPath("$.data.orderString").value(containsString("alipay.trade.app.pay")))
                .andExpect(jsonPath("$.data.orderString").value(containsString("sign=")));
    }

    @Test
    @DisplayName("5. 主动查单：返回强类型支付状态与流水号")
    void shouldReturnQueryOrderResult() throws Exception {
        PayOrderQueryDTO dto = PayOrderQueryDTO.builder()
                .channel(PayChannel.WECHAT_PAY)
                .outTradeNo("ORD_QUERY_20261023_005")
                .build();

        mockMvc.perform(post("/sample/pay/checkout/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.outTradeNo").value("ORD_QUERY_20261023_005"))
                .andExpect(jsonPath("$.data.transactionId").value("TRANSACTION_20261023_001"))
                .andExpect(jsonPath("$.data.tradeState").value("SUCCESS"));
    }

    @Test
    @DisplayName("6. 主动关单：返回已关闭状态")
    void shouldReturnCloseOrderResult() throws Exception {
        PayOrderCloseDTO dto = PayOrderCloseDTO.builder()
                .channel(PayChannel.WECHAT_PAY)
                .outTradeNo("ORD_CLOSE_20261023_006")
                .build();

        mockMvc.perform(post("/sample/pay/checkout/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.outTradeNo").value("ORD_CLOSE_20261023_006"))
                .andExpect(jsonPath("$.data.tradeState").value("CLOSED"));
    }

    @Test
    @DisplayName("7. 第一道防线 JSR-303 格式校验：金额非正数时 Fail-Fast 拦截并返回友好错误提示")
    void shouldFailFastWhenAmountIsInvalid() throws Exception {
        PayCheckoutDTO dto = PayCheckoutDTO.builder()
                .channel(PayChannel.WECHAT_PAY)
                .outTradeNo("ORD_INVALID_007")
                .amount(new BigDecimal("-5.00"))
                .subject("非法金额测试")
                .build();

        mockMvc.perform(post("/sample/pay/checkout/native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("8. 第一道防线 JSR-303 格式校验：缺少必要订单号时 Fail-Fast 拦截")
    void shouldFailFastWhenOutTradeNoIsBlank() throws Exception {
        PayCheckoutDTO dto = PayCheckoutDTO.builder()
                .channel(PayChannel.WECHAT_PAY)
                .outTradeNo("")
                .amount(new BigDecimal("10.00"))
                .subject("缺少单号测试")
                .build();

        mockMvc.perform(post("/sample/pay/checkout/native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("9. 业务回调监听器 SPI：首次处理返回 SUCCESS，重复投递触发防重返回 IGNORE")
    void shouldHandlePaymentCallbackWithIdempotentDeduplication() {
        PaymentSuccessCallback callback = PaymentSuccessCallback.builder()
                .channel(PayChannel.WECHAT_PAY)
                .merchantKey("default")
                .outTradeNo("ORD_IDEMPOTENT_009")
                .transactionId("TX_IDEMPOTENT_009")
                .amount(new BigDecimal("88.88"))
                .payerId("openid_test_user")
                .payTime("2026-10-23T22:30:00+08:00")
                .tradeState(PayTradeState.SUCCESS)
                .build();

        // 首次消费：更新状态并成功确认
        PayCallbackResult firstResult = callbackListener.onPaymentSuccess(callback);
        assertThat(firstResult.getStatus()).isEqualTo(PayCallbackResult.Status.SUCCESS);
        assertThat(firstResult.getMessage()).contains("更新成功");
        assertThat(callbackListener.getProcessedOrders()).contains("ORD_IDEMPOTENT_009");

        // 二次重投：检测到已处理过，静默忽略返回 IGNORE
        PayCallbackResult duplicateResult = callbackListener.onPaymentSuccess(callback);
        assertThat(duplicateResult.getStatus()).isEqualTo(PayCallbackResult.Status.IGNORE);
        assertThat(duplicateResult.getMessage()).contains("忽略重复通知");
    }

    @Test
    @DisplayName("10. 统一 Webhook 网关端点：非法渠道路径返回 400 拦截")
    void shouldRejectUnsupportedWebhookChannel() throws Exception {
        mockMvc.perform(post("/ateng-pay/callback/unknown_channel"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("11. 统一 Webhook 网关端点：支付宝回调缺少合法签名时返回 fail 官方应答")
    void shouldReturnFailAckWhenAlipaySignatureInvalid() throws Exception {
        mockMvc.perform(post("/ateng-pay/callback/alipay/default")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("out_trade_no", "ORD_MOCK_ALI_011")
                        .param("trade_status", "TRADE_SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(content().string("fail"));
    }

    @Test
    @DisplayName("12. 发起退款：成功生成退款申请并返回 PROCESSING 状态契约")
    void shouldApplyRefundSuccessfully() throws Exception {
        PayRefundDTO dto = PayRefundDTO.builder()
                .channel(PayChannel.WECHAT_PAY)
                .outTradeNo("ORD_NATIVE_20261023_001")
                .outRefundNo("REF_20261024_012")
                .refundAmount(new BigDecimal("20.00"))
                .totalAmount(new BigDecimal("100.00"))
                .reason("用户申请部分退款")
                .build();

        mockMvc.perform(post("/sample/pay/refund/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.outRefundNo").value("REF_20261024_012"))
                .andExpect(jsonPath("$.data.refundAmount").value(20.00))
                .andExpect(jsonPath("$.data.refundState").value("PROCESSING"));
    }

    @Test
    @DisplayName("13. 查询退款：返回退款成功 SUCCESS 强类型契约")
    void shouldQueryRefundSuccessfully() throws Exception {
        PayRefundQueryDTO dto = PayRefundQueryDTO.builder()
                .channel(PayChannel.WECHAT_PAY)
                .outRefundNo("REF_20261024_012")
                .build();

        mockMvc.perform(post("/sample/pay/refund/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.outRefundNo").value("REF_20261024_012"))
                .andExpect(jsonPath("$.data.refundState").value("SUCCESS"));
    }

    @Test
    @DisplayName("14. 退款回调监听器 SPI：首次处理返回 SUCCESS，重复投递触发防重返回 IGNORE")
    void shouldHandleRefundCallbackWithIdempotentDeduplication() {
        RefundSuccessCallback callback = RefundSuccessCallback.builder()
                .channel(PayChannel.WECHAT_PAY)
                .merchantKey("default")
                .outTradeNo("ORD_NATIVE_20261023_001")
                .transactionId("TRANSACTION_20261023_001")
                .outRefundNo("REF_IDEMPOTENT_014")
                .refundId("REFUND_TX_20261024_001")
                .refundAmount(new BigDecimal("20.00"))
                .totalAmount(new BigDecimal("100.00"))
                .refundStatus("SUCCESS")
                .refundTime("2026-10-24T10:00:00+08:00")
                .build();

        assertThat(callbackListener.supports(callback)).isTrue();

        // 首次消费
        PayCallbackResult firstResult = callbackListener.onRefundSuccess(callback);
        assertThat(firstResult.getStatus()).isEqualTo(PayCallbackResult.Status.SUCCESS);
        assertThat(firstResult.getMessage()).contains("退款状态更新成功");
        assertThat(callbackListener.getProcessedRefunds()).contains("REF_IDEMPOTENT_014");

        // 重复消费
        PayCallbackResult duplicateResult = callbackListener.onRefundSuccess(callback);
        assertThat(duplicateResult.getStatus()).isEqualTo(PayCallbackResult.Status.IGNORE);
        assertThat(duplicateResult.getMessage()).contains("忽略重复通知");
    }

    @Test
    @DisplayName("15. 单笔转账发起：返回 SUCCESS 转账状态与单据号")
    void shouldApplySingleTransferSuccessfully() throws Exception {
        PayTransferDTO dto = PayTransferDTO.builder()
                .channel(PayChannel.ALIPAY)
                .outBizNo("TRANS_BIZ_20261024_015")
                .amount(new BigDecimal("50.00"))
                .title("佣金提现结算")
                .payeeAccount("alipay_user@example.com")
                .payeeRealName("张三")
                .build();

        mockMvc.perform(post("/sample/pay/transfer/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.outBizNo").value("TRANS_BIZ_20261024_015"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.orderId").value("TRANSFER_TX_20261024_001"));
    }

    @Test
    @DisplayName("16. 单笔转账查询：返回当前转账状态")
    void shouldQuerySingleTransferSuccessfully() throws Exception {
        PayTransferQueryDTO dto = PayTransferQueryDTO.builder()
                .channel(PayChannel.ALIPAY)
                .outBizNo("TRANS_BIZ_20261024_015")
                .build();

        mockMvc.perform(post("/sample/pay/transfer/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.outBizNo").value("TRANS_BIZ_20261024_015"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
    }

    @Test
    @DisplayName("17. 条码收银支付：同步轮询扣款并返回 SUCCESS 支付结果")
    void shouldCashierBarcodePaySuccessfully() throws Exception {
        PayBarcodeDTO dto = PayBarcodeDTO.builder()
                .channel(PayChannel.WECHAT_PAY)
                .outTradeNo("ORD_BARCODE_20261024_017")
                .amount(new BigDecimal("15.00"))
                .authCode("134567890123456789")
                .subject("收银台面对面扫码收单")
                .build();

        mockMvc.perform(post("/sample/pay/barcode/cashier")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tradeType").value("BARCODE"))
                .andExpect(jsonPath("$.data.tradeState").value("SUCCESS"))
                .andExpect(jsonPath("$.data.outTradeNo").value("ORD_BARCODE_20261024_017"));
    }

    @Test
    @DisplayName("18. 财务对账单流式下载与全量内存解析：返回明细列表与汇总统计")
    void shouldDownloadAndParseBillInMemory() throws Exception {
        PayBillQueryDTO dto = PayBillQueryDTO.builder()
                .channel(PayChannel.WECHAT_PAY)
                .billDate("2026-10-24")
                .billType("ALL")
                .tarType("GZIP")
                .build();

        mockMvc.perform(post("/sample/pay/bill/download/parse-memory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.entries").isArray())
                .andExpect(jsonPath("$.data.entries.length()").value(2))
                .andExpect(jsonPath("$.data.entries[0].outTradeNo").value("WX_OUT_001"))
                .andExpect(jsonPath("$.data.summary.totalCount").value(2))
                .andExpect(jsonPath("$.data.summary.totalAmount").value(150.00));
    }

    @Test
    @DisplayName("19. 财务对账单零 OOM 流式边解压边消费：返回流式汇总统计")
    void shouldStreamReconcileBillZeroOom() throws Exception {
        PayBillQueryDTO dto = PayBillQueryDTO.builder()
                .channel(PayChannel.WECHAT_PAY)
                .billDate("2026-10-24")
                .billType("ALL")
                .tarType("GZIP")
                .build();

        mockMvc.perform(post("/sample/pay/bill/download/stream-consume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.totalAmount").value(150.00));
    }

    @Test
    @DisplayName("20. 微信资金分账：添加分账接收方与完结解冻分账订单")
    void shouldAddProfitSharingReceiverAndUnfreeze() throws Exception {
        // 1. 添加接收方
        ProfitSharingReceiverDTO receiverDTO = ProfitSharingReceiverDTO.builder()
                .type("PERSONAL_OPENID")
                .account("oUpF8uMuAJO_M2pxb1Q9zNjWeS6o")
                .relationType("USER")
                .build();

        mockMvc.perform(post("/sample/pay/profit-sharing/receivers/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(receiverDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.account").value("oUpF8uMuAJO_M2pxb1Q9zNjWeS6o"));

        // 2. 解冻分账
        ProfitSharingUnfreezeDTO unfreezeDTO = ProfitSharingUnfreezeDTO.builder()
                .outOrderNo("ORD_UNFREEZE_001")
                .transactionId("TRANSACTION_20261024_001")
                .description("结算分账完毕，解冻剩余资金")
                .build();

        mockMvc.perform(post("/sample/pay/profit-sharing/unfreeze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(unfreezeDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.outOrderNo").value("ORD_UNFREEZE_001"));
    }

    @Test
    @DisplayName("21. 支付宝小程序收单：调用真实 alipay.trade.create 成功返回 tradeNo 凭据")
    void shouldReturnTradeNoForAlipayMiniAppCheckout() throws Exception {
        PayCheckoutDTO dto = PayCheckoutDTO.builder()
                .channel(PayChannel.ALIPAY)
                .outTradeNo("ORD_ALI_MINI_001")
                .amount(new BigDecimal("88.00"))
                .subject("支付宝小程序购买云服务")
                .buyerId("2088102123456789")
                .build();

        mockMvc.perform(post("/sample/pay/checkout/alipay-miniapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.channel").value("ALIPAY"))
                .andExpect(jsonPath("$.data.tradeType").value("JSAPI"))
                .andExpect(jsonPath("$.data.outTradeNo").value("ORD_ALI_MINI_001"))
                .andExpect(jsonPath("$.data.tradeNo").value("202610232200140099"))
                .andExpect(jsonPath("$.data.transactionId").value("202610232200140099"));
    }

    @Test
    @DisplayName("22. 条码收银等待超时自动撤销冲正：全真执行冲正 /reverse 接口并返回 REVOKED 终态")
    void shouldCancelAndReverseBarcodeCashierOnTimeout() throws Exception {
        PayBarcodeDTO dto = PayBarcodeDTO.builder()
                .channel(PayChannel.WECHAT_PAY)
                .outTradeNo("ORD_REVERSE_TIMEOUT_022")
                .amount(new BigDecimal("66.00"))
                .authCode("134567890123456789")
                .subject("收银超时冲正自验")
                .maxWaitTime(Duration.ofMillis(300))
                .pollInterval(Duration.ofMillis(50))
                .autoCancelOnTimeout(true)
                .build();

        mockMvc.perform(post("/sample/pay/barcode/cashier")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.tradeType").value("BARCODE"))
                .andExpect(jsonPath("$.data.tradeState").value("REVOKED"))
                .andExpect(jsonPath("$.data.tradeStateDesc").value(containsString("系统已自动发起撤销冲正")));
    }

    // =========================================================================
    // 6. 撤销、新版商家转账、双渠道全量分账、双文件账单与终态回调端到端回归 (#1067)
    // =========================================================================

    @Test
    @DisplayName("23. 独立条码/订单撤销：覆盖微信/支付宝撤销结果透传与 JSR-303 400 拦截")
    void shouldCancelBarcodeOrderForBothChannelsAndValidate() throws Exception {
        // 1. 微信独立撤销
        String wechatCancelJson = """
                {
                  "channel": "WECHAT_PAY",
                  "outTradeNo": "ORD_CANCEL_WX_023"
                }
                """;
        mockMvc.perform(post("/sample/pay/barcode/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wechatCancelJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.channel").value("WECHAT_PAY"))
                .andExpect(jsonPath("$.data.outTradeNo").value("ORD_CANCEL_WX_023"))
                .andExpect(jsonPath("$.data.tradeState").value("REVOKED"));

        // 2. 支付宝独立撤销
        String alipayCancelJson = """
                {
                  "channel": "ALIPAY",
                  "outTradeNo": "ORD_CANCEL_ALI_023"
                }
                """;
        mockMvc.perform(post("/sample/pay/barcode/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(alipayCancelJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.channel").value("ALIPAY"))
                .andExpect(jsonPath("$.data.outTradeNo").value("ORD_CANCEL_ALI_023"))
                .andExpect(jsonPath("$.data.tradeState").value("REVOKED"));

        // 3. JSR-303 非空拦截 (Result code=400)
        String invalidJson = """
                {
                  "channel": "WECHAT_PAY",
                  "outTradeNo": ""
                }
                """;
        mockMvc.perform(post("/sample/pay/barcode/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("24. 微信新版用户确认模式商家转账全生命周期：发起返回 packageInfo、查询与主动撤销")
    void shouldExecuteWechatNewMerchantTransferApplyQueryAndCancel() throws Exception {
        // 1. 发起新版商家转账（携带 transferSceneId 与报备明细）
        String applyJson = """
                {
                  "channel": "WECHAT_PAY",
                  "outBizNo": "MCH_SAMPLE_TRANSFER_024",
                  "amount": 200.00,
                  "title": "合伙人补贴发放",
                  "remark": "2026-11 奖励补贴",
                  "payeeAccount": "oUpF8uMuAJO_M2pxb1Q9zNjWeS6o",
                  "payeeRealName": "张三",
                  "notifyUrl": "https://example.com/transfer/notify",
                  "transferSceneId": "1000",
                  "transferSceneReportInfos": [
                    {
                      "infoType": "活动名称",
                      "infoContent": "渠道拉新奖励"
                    }
                  ],
                  "legacyBatchMode": false
                }
                """;
        mockMvc.perform(post("/sample/pay/transfer/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.channel").value("WECHAT_PAY"))
                .andExpect(jsonPath("$.data.outBizNo").value("MCH_SAMPLE_TRANSFER_024"))
                .andExpect(jsonPath("$.data.status").value("WAIT_USER_CONFIRM"))
                .andExpect(jsonPath("$.data.packageInfo").value("MOCK_PACKAGE_INFO_024"));

        // 2. 查询转账单状态
        String queryJson = """
                {
                  "channel": "WECHAT_PAY",
                  "outBizNo": "MCH_SAMPLE_TRANSFER_024"
                }
                """;
        mockMvc.perform(post("/sample/pay/transfer/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(queryJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.outBizNo").value("MCH_SAMPLE_TRANSFER_024"))
                .andExpect(jsonPath("$.data.status").value("WAIT_USER_CONFIRM"));

        // 3. 主动撤销转账单
        String cancelJson = """
                {
                  "channel": "WECHAT_PAY",
                  "outBizNo": "MCH_SAMPLE_TRANSFER_024"
                }
                """;
        mockMvc.perform(post("/sample/pay/transfer/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.outBizNo").value("MCH_SAMPLE_TRANSFER_024"))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        assertThat(capturedOutboundRequests).anySatisfy(trace -> {
            assertThat(trace).contains("POST /v3/fund-app/mch-transfer/transfer-bills");
            assertThat(trace).contains("serial=PUB_KEY_ID_TEST_001");
            assertThat(trace).contains("\"transfer_scene_id\":\"1000\"");
            assertThat(trace).contains("\"info_type\":\"活动名称\"");
            assertThat(trace).contains("\"user_name\":\"");
            assertThat(trace).doesNotContain("\"user_name\":\"张三\"");
        });
        assertThat(capturedOutboundRequests).anySatisfy(trace ->
                assertThat(trace).contains("POST /v3/fund-app/mch-transfer/transfer-bills/out-bill-no/MCH_SAMPLE_TRANSFER_024/cancel"));

        // 4. 撤销参数缺失拦截 (Result code=400)
        mockMvc.perform(post("/sample/pay/transfer/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"WECHAT_PAY\",\"outBizNo\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("25. 微信资金分账完整生命周期：接收方增删、发起分账、查询、回退与回退查询、剩余金额与比例查询")
    void shouldExecuteFullWechatProfitSharingLifecycle() throws Exception {
        // 1. 请求分账
        String createOrderJson = """
                {
                  "transactionId": "TRANSACTION_20261118_025",
                  "outOrderNo": "PS_SAMPLE_025",
                  "unfreezeUnsplit": false,
                  "receivers": [
                    {
                      "type": "PERSONAL_OPENID",
                      "account": "oUpF8uMuAJO_M2pxb1Q9zNjWeS6o",
                      "amount": 100,
                      "description": "门店推广佣金"
                    }
                  ]
                }
                """;
        mockMvc.perform(post("/sample/pay/profit-sharing/orders/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.outOrderNo").value("PS_SAMPLE_025"))
                .andExpect(jsonPath("$.data.orderId").value("3000002000202610240001"));

        // 2. 查询分账结果
        mockMvc.perform(post("/sample/pay/profit-sharing/orders/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outOrderNo\":\"PS_SAMPLE_025\",\"transactionId\":\"TRANSACTION_20261118_025\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.outOrderNo").value("PS_SAMPLE_025"));

        // 3. 查询剩余待分金额
        mockMvc.perform(post("/sample/pay/profit-sharing/amounts/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":\"TRANSACTION_20261118_025\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.unsplitAmount").value(500));

        // 4. 查询最大分账比例
        mockMvc.perform(post("/sample/pay/profit-sharing/merchant-ratios/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subMchid\":\"1900000002\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.maxRatio").value(3000));

        // 5. 请求分账回退
        String returnJson = """
                {
                  "outOrderNo": "PS_SAMPLE_025",
                  "outReturnNo": "RET_SAMPLE_025",
                  "returnMchid": "1900000002",
                  "amount": 100,
                  "description": "退款触发分账回退"
                }
                """;
        mockMvc.perform(post("/sample/pay/profit-sharing/return-orders/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(returnJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.outReturnNo").value("RET_SAMPLE_025"));

        // 6. 查询分账回退结果
        mockMvc.perform(post("/sample/pay/profit-sharing/return-orders/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outReturnNo\":\"RET_SAMPLE_025\",\"outOrderNo\":\"PS_SAMPLE_025\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.outReturnNo").value("RET_SAMPLE_025"));

        // 7. 删除分账接收方
        mockMvc.perform(post("/sample/pay/profit-sharing/receivers/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"PERSONAL_OPENID\",\"account\":\"oUpF8uMuAJO_M2pxb1Q9zNjWeS6o\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.account").value("oUpF8uMuAJO_M2pxb1Q9zNjWeS6o"));

        // 8. 缺失接收方明细触发 JSR-303 400
        mockMvc.perform(post("/sample/pay/profit-sharing/orders/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":\"TX_01\",\"outOrderNo\":\"PS_01\",\"receivers\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("26. 支付宝前置冻结收单与分账全链路：royaltyFreeze、关系绑定/查询/解绑、结算确认与分账结算")
    void shouldExecuteFullAlipayProfitSharingLifecycle() throws Exception {
        // 1. 支付宝前置分账冻结扫码预下单 (extend_params.royalty_freeze=true)
        String preFreezeJson = """
                {
                  "outTradeNo": "ALI_FRZ_026",
                  "amount": 99.00,
                  "subject": "前置冻结分账商品",
                  "royaltyFreeze": true,
                  "appAuthToken": "isv_sample_token_026"
                }
                """;
        mockMvc.perform(post("/sample/pay/profit-sharing/alipay/pre-freeze-qr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(preFreezeJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.channel").value("ALIPAY"))
                .andExpect(jsonPath("$.data.qrCode").isNotEmpty());

        // 2. 分账关系绑定
        String bindJson = """
                {
                  "outRequestNo": "ALI_BIND_026",
                  "appAuthToken": "isv_sample_token_026",
                  "receivers": [
                    {
                      "type": "userId",
                      "account": "2088101126708402",
                      "name": "合作方A",
                      "memo": "供应商分成"
                    }
                  ]
                }
                """;
        mockMvc.perform(post("/sample/pay/profit-sharing/alipay/receivers/bind")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.resultCode").value("SUCCESS"));

        // 3. 分账关系批量查询
        mockMvc.perform(post("/sample/pay/profit-sharing/alipay/receivers/batchquery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outRequestNo\":\"ALI_QUERY_REL_026\",\"pageNum\":1,\"pageSize\":20,\"appAuthToken\":\"isv_sample_token_026\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.receiverList[0].account").value("2088101126708402"));

        // 4. 交易结算确认
        String confirmJson = """
                {
                  "outRequestNo": "ALI_CONFIRM_026",
                  "tradeNo": "202611182200140026",
                  "amount": 99.00,
                  "appAuthToken": "isv_sample_token_026"
                }
                """;
        mockMvc.perform(post("/sample/pay/profit-sharing/alipay/settle/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.settleAmount").value("99.00"));

        // 5. 统一收单交易分账结算
        String settleJson = """
                {
                  "outRequestNo": "ALI_SETTLE_026",
                  "tradeNo": "202611182200140026",
                  "appAuthToken": "isv_sample_token_026",
                  "royaltyParameters": [
                    {
                      "transInType": "userId",
                      "transIn": "2088101126708402",
                      "amount": 19.80,
                      "desc": "渠道合作分账"
                    }
                  ]
                }
                """;
        mockMvc.perform(post("/sample/pay/profit-sharing/alipay/settle/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settleJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.settleNo").value("20261118SETTLE026"));

        // 6. 分账进度查询
        mockMvc.perform(post("/sample/pay/profit-sharing/alipay/settle/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settleNo\":\"20261118SETTLE026\",\"appAuthToken\":\"isv_sample_token_026\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.outRequestNo").value("ALI_SETTLE_026"));

        // 7. 分账关系解绑
        mockMvc.perform(post("/sample/pay/profit-sharing/alipay/receivers/unbind")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.resultCode").value("SUCCESS"));

        assertThat(capturedOutboundRequests).anySatisfy(trace -> {
            assertThat(trace).contains("alipay.trade.precreate");
            assertThat(trace).contains("\"royalty_freeze\":\"true\"");
            assertThat(trace).contains("isv_sample_token_026");
        });
        assertThat(capturedOutboundRequests).anySatisfy(trace -> {
            assertThat(trace).contains("alipay.trade.order.settle");
            assertThat(trace).contains("isv_sample_token_026");
        });

        // 8. 缺失结算明细触发 JSR-303 400
        mockMvc.perform(post("/sample/pay/profit-sharing/alipay/settle/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outRequestNo\":\"S1\",\"tradeNo\":\"T1\",\"royaltyParameters\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("27. 支付宝标准双 CSV ZIP 对账单端到端下载与流式解析（明细+汇总双条目准确识别）")
    void shouldDownloadAndParseAlipayDualCsvZipBill() throws Exception {
        PayBillQueryDTO dto = PayBillQueryDTO.builder()
                .channel(PayChannel.ALIPAY)
                .billDate("2026-11-18")
                .billType("trade")
                .build();

        mockMvc.perform(post("/sample/pay/bill/download/parse-memory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.entries.length()").value(2))
                .andExpect(jsonPath("$.data.entries[0].outTradeNo").value("ALI_OUT_001"))
                .andExpect(jsonPath("$.data.summary.channel").value("ALIPAY"))
                .andExpect(jsonPath("$.data.summary.totalCount").value(2))
                .andExpect(jsonPath("$.data.summary.totalAmount").value(120.00))
                .andExpect(jsonPath("$.data.summary.settlementAmount").value(119.30));

        mockMvc.perform(post("/sample/pay/bill/download/stream-consume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.channel").value("ALIPAY"))
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.serviceFee").value(0.70));
    }

    @Test
    @DisplayName("28. 关单与转账回调监听器 SPI：首次消费写入幂等集合，重复投递自动忽略")
    void shouldHandleOrderClosedAndTransferCallbacksIdempotently() {
        OrderClosedCallback closedCb = OrderClosedCallback.builder()
                .channel(PayChannel.WECHAT_PAY)
                .merchantKey("default")
                .outTradeNo("ORD_CLOSED_IDEMP_028")
                .tradeState(PayTradeState.CLOSED)
                .rawTradeState("CLOSED")
                .closeReason("超时关单")
                .build();

        assertThat(callbackListener.supports(closedCb)).isTrue();
        PayCallbackResult firstClosed = callbackListener.onOrderClosed(closedCb);
        assertThat(firstClosed.getStatus()).isEqualTo(PayCallbackResult.Status.SUCCESS);
        assertThat(callbackListener.getProcessedClosedOrders()).contains("ORD_CLOSED_IDEMP_028");
        assertThat(callbackListener.onOrderClosed(closedCb).getStatus()).isEqualTo(PayCallbackResult.Status.IGNORE);

        TransferNotifyCallback transferCb = TransferNotifyCallback.builder()
                .channel(PayChannel.WECHAT_PAY)
                .merchantKey("default")
                .outBizNo("MCH_TRANSFER_IDEMP_028")
                .orderId("13300000999028")
                .status(PayTransferState.SUCCESS)
                .amount(new BigDecimal("88.88"))
                .payeeAccount("oUpF8uMuAJO_M2pxb1Q9zNjWeS6o")
                .build();

        assertThat(callbackListener.supports(transferCb)).isTrue();
        PayCallbackResult firstTransfer = callbackListener.onTransferNotify(transferCb);
        assertThat(firstTransfer.getStatus()).isEqualTo(PayCallbackResult.Status.SUCCESS);
        assertThat(callbackListener.getProcessedTransfers()).contains("MCH_TRANSFER_IDEMP_028");
        assertThat(callbackListener.onTransferNotify(transferCb).getStatus()).isEqualTo(PayCallbackResult.Status.IGNORE);
    }

    @Test
    @DisplayName("29. 网关 Webhook 端到端灰度和非终态守卫：NOTPAY 忽略、CLOSED 路由关单、支付宝 WAIT_BUYER_PAY 放行不入账、灰度双轨验签")
    void shouldEnforceNonSuccessGuardAndGrayscaleSignatureInWebhookEndpoints() throws Exception {
        // 1. 微信 NOTPAY 绝不触发支付成功监听器
        String notPayResource = """
                {
                  "mchid": "1900000001",
                  "appid": "wx8888888888888888",
                  "out_trade_no": "ORD_NOTPAY_029",
                  "trade_state": "NOTPAY",
                  "trade_state_desc": "订单未支付"
                }
                """;
        mockMvc.perform(buildSignedWechatCallbackRequest(
                        "/ateng-pay/callback/wechat/default",
                        "TRANSACTION.SUCCESS", "transaction", notPayResource, "PUB_KEY_ID_TEST_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
        assertThat(callbackListener.getProcessedOrders()).doesNotContain("ORD_NOTPAY_029");

        // 2. 微信 CLOSED 路由至 onOrderClosed
        String closedResource = """
                {
                  "mchid": "1900000001",
                  "appid": "wx8888888888888888",
                  "out_trade_no": "ORD_CLOSED_CB_029",
                  "trade_state": "CLOSED",
                  "trade_state_desc": "订单已关闭"
                }
                """;
        mockMvc.perform(buildSignedWechatCallbackRequest(
                        "/ateng-pay/callback/wechat/default",
                        "TRANSACTION.SUCCESS", "transaction", closedResource, "PUB_KEY_ID_TEST_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
        assertThat(callbackListener.getProcessedClosedOrders()).contains("ORD_CLOSED_CB_029");
        assertThat(callbackListener.getProcessedOrders()).doesNotContain("ORD_CLOSED_CB_029");

        // 3. 支付宝 WAIT_BUYER_PAY 返回 success ACK 且不触发支付入账
        Map<String, String> aliWaitParams = new HashMap<>();
        aliWaitParams.put("app_id", "2021000000000001");
        aliWaitParams.put("out_trade_no", "ORD_ALI_WAIT_029");
        aliWaitParams.put("trade_no", "202611182200140299");
        aliWaitParams.put("trade_status", "WAIT_BUYER_PAY");
        aliWaitParams.put("total_amount", "99.00");
        mockMvc.perform(buildSignedAlipayCallbackRequest("/ateng-pay/callback/alipay/default", aliWaitParams))
                .andExpect(status().isOk())
                .andExpect(content().string("success"));
        assertThat(callbackListener.getProcessedOrders()).doesNotContain("ORD_ALI_WAIT_029");

        // 4. 微信灰度双轨商户（同时开启公钥 + autoCertificate=true）：兼容公钥 ID 与旧平台证书序列号
        X509Certificate cert = PayKeyLoader.loadCertificate(PayKeyLoader.loadString("classpath:certs/test_certificate.pem"));
        String certSerial = cert.getSerialNumber().toString(16).toUpperCase();
        certificateManager.registerCertificates("1900000090", Map.of(certSerial, cert));

        String grayPubJson = """
                {
                  "mchid": "1900000090",
                  "appid": "wx8888888888888888",
                  "out_trade_no": "ORD_GRAY_PUB_029",
                  "transaction_id": "WX_GRAY_TX_PUB_029",
                  "trade_state": "SUCCESS",
                  "amount": {"total": 1000, "payer_total": 1000, "currency": "CNY"}
                }
                """;
        mockMvc.perform(buildSignedWechatCallbackRequest(
                        "/ateng-pay/callback/wechat/grayscale",
                        "TRANSACTION.SUCCESS", "transaction", grayPubJson, "PUB_KEY_ID_GRAY_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
        assertThat(callbackListener.getProcessedOrders()).contains("ORD_GRAY_PUB_029");

        String grayCertJson = """
                {
                  "mchid": "1900000090",
                  "appid": "wx8888888888888888",
                  "out_trade_no": "ORD_GRAY_CERT_029",
                  "transaction_id": "WX_GRAY_TX_CERT_029",
                  "trade_state": "SUCCESS",
                  "amount": {"total": 2000, "payer_total": 2000, "currency": "CNY"}
                }
                """;
        mockMvc.perform(buildSignedWechatCallbackRequest(
                        "/ateng-pay/callback/wechat/grayscale",
                        "TRANSACTION.SUCCESS", "transaction", grayCertJson, certSerial))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
        assertThat(callbackListener.getProcessedOrders()).contains("ORD_GRAY_CERT_029");
    }

    @Test
    @DisplayName("30. 微信新版商家转账回调 MCHTRANSFER.BILL.FINISHED 端到端网关回归")
    void shouldHandleWechatTransferCallbackEndToEndViaWebhook() throws Exception {
        String transferJson = """
                {
                  "mch_id": "1900000001",
                  "out_bill_no": "MCH_CB_END2END_030",
                  "transfer_bill_no": "133000007110109690030000030",
                  "state": "SUCCESS",
                  "transfer_amount": 8800,
                  "openid": "oUpF8uMuAJO_M2pxb1Q9zNjWeS6o",
                  "update_time": "2026-11-18T16:30:00+08:00"
                }
                """;
        mockMvc.perform(buildSignedWechatCallbackRequest(
                        "/ateng-pay/callback/wechat/default",
                        "MCHTRANSFER.BILL.FINISHED", "transfer_bill", transferJson, "PUB_KEY_ID_TEST_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
        assertThat(callbackListener.getProcessedTransfers()).contains("MCH_CB_END2END_030");
    }

    private MockHttpServletRequestBuilder buildSignedWechatCallbackRequest(String url,
                                                                           String eventType,
                                                                           String resourceType,
                                                                           String plainResourceJson,
                                                                           String serial) throws Exception {
        String nonce12 = "123456789012";
        String aad = resourceType;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec keySpec = new SecretKeySpec("32bytesMockApiV3KeyForTesting000".getBytes(StandardCharsets.UTF_8), "AES");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(128, nonce12.getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        String ciphertext = Base64.getEncoder().encodeToString(cipher.doFinal(plainResourceJson.getBytes(StandardCharsets.UTF_8)));

        String body = objectMapper.writeValueAsString(Map.of(
                "id", "EVT_" + System.nanoTime(),
                "create_time", "2026-11-18T16:30:00+08:00",
                "event_type", eventType,
                "resource_type", "encrypt-resource",
                "resource", Map.of(
                        "algorithm", "AEAD_AES_256_GCM",
                        "ciphertext", ciphertext,
                        "associated_data", aad,
                        "nonce", nonce12,
                        "original_type", resourceType
                )
        ));

        long timestamp = Instant.now().getEpochSecond();
        String nonceHeader = "cb_nonce_" + System.nanoTime();
        String payload = timestamp + "\n" + nonceHeader + "\n" + body + "\n";
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(rsaPrivateKey);
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(signer.sign());

        return post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Wechatpay-Timestamp", String.valueOf(timestamp))
                .header("Wechatpay-Nonce", nonceHeader)
                .header("Wechatpay-Signature", signature)
                .header("Wechatpay-Serial", serial)
                .content(body);
    }

    private MockHttpServletRequestBuilder buildSignedAlipayCallbackRequest(String url,
                                                                           Map<String, String> params) throws Exception {
        TreeMap<String, String> sorted = new TreeMap<>(params);
        String signContent = sorted.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(alipayPrivateKey);
        signer.update(signContent.getBytes(StandardCharsets.UTF_8));
        String sign = Base64.getEncoder().encodeToString(signer.sign());

        MockHttpServletRequestBuilder builder = post(url)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("sign_type", "RSA2")
                .param("sign", sign);
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            builder.param(entry.getKey(), entry.getValue());
        }
        return builder;
    }
}
