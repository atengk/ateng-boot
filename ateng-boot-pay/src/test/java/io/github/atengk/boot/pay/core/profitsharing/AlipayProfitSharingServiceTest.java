package io.github.atengk.boot.pay.core.profitsharing;

import com.alipay.api.domain.AlipayTradeOrderSettleModel;
import com.alipay.api.domain.AlipayTradeOrderSettleQueryModel;
import com.alipay.api.domain.AlipayTradeRoyaltyRelationBatchqueryModel;
import com.alipay.api.domain.AlipayTradeRoyaltyRelationBindModel;
import com.alipay.api.domain.AlipayTradeRoyaltyRelationUnbindModel;
import com.alipay.api.domain.AlipayTradeSettleConfirmModel;
import com.alipay.api.domain.OpenApiRoyaltyDetailInfoPojo;
import com.alipay.api.domain.RoyaltyEntity;
import com.alipay.api.domain.SettleDetailInfo;
import com.alipay.api.domain.SettleInfo;
import io.github.atengk.boot.pay.config.properties.AlipayAppProperties;
import io.github.atengk.boot.pay.config.properties.PayProperties;
import io.github.atengk.boot.pay.core.AlipayTemplate;
import io.github.atengk.boot.pay.core.merchant.NamedMerchantRegistry;
import io.github.atengk.boot.pay.enums.PayResultCode;
import io.github.atengk.boot.pay.exception.PayException;
import io.github.atengk.boot.pay.model.request.AlipayPayRequest;
import io.github.atengk.boot.pay.model.response.PayResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 支付宝交易分账门面 AlipayProfitSharingService 与前置冻结收单 100% 离线自洽测试
 *
 * @author Ateng
 * @since 2026-11-18
 */
class AlipayProfitSharingServiceTest {

    private MockWebServer server;
    private PrivateKey alipayPrivateKey;
    private PayProperties props;
    private AlipayTemplate alipayTemplate;
    private AlipayProfitSharingService profitSharingService;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();

        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);

        KeyPair merchantKeyPair = keyGen.generateKeyPair();
        String merchantPrivateKeyPem = Base64.getEncoder().encodeToString(merchantKeyPair.getPrivate().getEncoded());

        KeyPair alipayKeyPair = keyGen.generateKeyPair();
        alipayPrivateKey = alipayKeyPair.getPrivate();
        String alipayPublicKeyPem = Base64.getEncoder().encodeToString(alipayKeyPair.getPublic().getEncoded());

        props = new PayProperties();
        AlipayAppProperties isvApp = new AlipayAppProperties();
        isvApp.setAppId("2021000000000501");
        isvApp.setPrivateKey(merchantPrivateKeyPem);
        isvApp.setAlipayPublicKey(alipayPublicKeyPem);
        isvApp.setAppAuthToken("isv_royalty_token_888");
        isvApp.setNotifyUrl("https://api.test.com/notify");
        isvApp.setServerUrl(server.url("/gateway.do").toString());

        props.getAlipay().setDefaultApp("isv");
        props.getAlipay().getApps().put("isv", isvApp);

        alipayTemplate = new AlipayTemplate(new NamedMerchantRegistry(props), props);
        profitSharingService = new AlipayProfitSharingService(alipayTemplate);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    @DisplayName("物理总开关关闭时调用 AlipayProfitSharingService 立即 Fail-Fast 拦截且不污染共享 PayProperties")
    void testProfitSharingDisabledFailsFast() {
        boolean initialSharedState = alipayTemplate.isProfitSharingEnabled();
        alipayTemplate.setProfitSharingEnabled(false);

        AlipayTradeRoyaltyRelationBindModel bindModel = new AlipayTradeRoyaltyRelationBindModel();
        bindModel.setOutRequestNo("REQ_DISABLED_01");
        PayException ex1 = assertThrows(PayException.class, () -> profitSharingService.bindRelation(bindModel));
        assertEquals(PayResultCode.PAY_FEATURE_DISABLED, ex1.getResultCode());

        PayException ex2 = assertThrows(PayException.class,
                () -> profitSharingService.orderSettle(new AlipayTradeOrderSettleModel()));
        assertEquals(PayResultCode.PAY_FEATURE_DISABLED, ex2.getResultCode());

        // 验证实例级覆写未修改原始共享 PayProperties 状态
        alipayTemplate.setProfitSharingEnabled(true);
        assertFalse(initialSharedState);
        assertFalse(props.getProfitSharing().isEnabled());
    }

    @Test
    @DisplayName("参数边界防卫: 缺失必要入参抛出 PAY_PARAM_INVALID")
    void testValidationGuards() {
        alipayTemplate.setProfitSharingEnabled(true);

        assertEquals(PayResultCode.PAY_PARAM_INVALID,
                assertThrows(PayException.class, () -> profitSharingService.bindRelation(null)).getResultCode());
        assertEquals(PayResultCode.PAY_PARAM_INVALID,
                assertThrows(PayException.class, () -> profitSharingService.unbindRelation(new AlipayTradeRoyaltyRelationUnbindModel())).getResultCode());
        assertEquals(PayResultCode.PAY_PARAM_INVALID,
                assertThrows(PayException.class, () -> profitSharingService.batchQueryRelation(new AlipayTradeRoyaltyRelationBatchqueryModel())).getResultCode());
        assertEquals(PayResultCode.PAY_PARAM_INVALID,
                assertThrows(PayException.class, () -> profitSharingService.confirmSettle(new AlipayTradeSettleConfirmModel())).getResultCode());
        assertEquals(PayResultCode.PAY_PARAM_INVALID,
                assertThrows(PayException.class, () -> profitSharingService.orderSettle(new AlipayTradeOrderSettleModel())).getResultCode());
        assertEquals(PayResultCode.PAY_PARAM_INVALID,
                assertThrows(PayException.class, () -> profitSharingService.queryOrderSettle(new AlipayTradeOrderSettleQueryModel())).getResultCode());
    }

    @Test
    @DisplayName("前置冻结收单与 6 组完整分账链路: 关系绑定/解绑/分页查询、结算确认、分账结算与查询 + app_auth_token 透传")
    void testFullAlipayRoyaltyLifecycleAndPreFreezeCheckout() throws Exception {
        alipayTemplate.setProfitSharingEnabled(true);

        // 0. 前置分账冻结与可选 SettleInfo 收单（扫码预下单 & 小程序创建）
        server.enqueue(jsonMock(buildSignedResponse("alipay_trade_precreate_response",
                "{\"code\":\"10000\",\"msg\":\"Success\",\"out_trade_no\":\"PRE_FRZ_01\",\"qr_code\":\"https://qr.alipay.com/frz\"}",
                alipayPrivateKey)));
        SettleDetailInfo settleDetail = new SettleDetailInfo();
        settleDetail.setTransInType("defaultSettle");
        settleDetail.setAmount("99.00");
        SettleInfo settleInfo = new SettleInfo();
        settleInfo.setSettleDetailInfos(List.of(settleDetail));
        settleInfo.setSettlePeriodTime("1d");

        PayResponse preResp = alipayTemplate.payQr(AlipayPayRequest.builder()
                .outTradeNo("PRE_FRZ_01")
                .subject("前置冻结测试订单")
                .amount(new BigDecimal("99.00"))
                .royaltyFreeze(true)
                .settleInfo(settleInfo)
                .build());
        assertTrue(preResp.isSuccess());

        RecordedRequest preReq = server.takeRequest();
        String preBody = URLDecoder.decode(preReq.getBody().readUtf8(), StandardCharsets.UTF_8);
        assertTrue(preBody.contains("\"royalty_freeze\":\"true\""), "前置冻结参数必须注入 extend_params");
        assertTrue(preBody.contains("\"settle_period_time\":\"1d\""), "可选结算参数 settle_info 必须透传");
        assertTrue(preBody.contains("isv_royalty_token_888") || preReq.getRequestUrl().toString().contains("isv_royalty_token_888"));

        // 1. 分账关系绑定 bind
        server.enqueue(jsonMock(buildSignedResponse("alipay_trade_royalty_relation_bind_response",
                "{\"code\":\"10000\",\"msg\":\"Success\",\"result_code\":\"SUCCESS\"}", alipayPrivateKey)));
        RoyaltyEntity receiver = new RoyaltyEntity();
        receiver.setType("userId");
        receiver.setAccount("2088101126708402");
        receiver.setName("合作方A");
        AlipayTradeRoyaltyRelationBindModel bindModel = new AlipayTradeRoyaltyRelationBindModel();
        bindModel.setOutRequestNo("BIND_001");
        bindModel.setReceiverList(List.of(receiver));
        assertEquals("SUCCESS", profitSharingService.bindRelation(bindModel).getResultCode());
        assertRequestCarriesTokenAndMethod(server.takeRequest(), "alipay.trade.royalty.relation.bind", "isv_royalty_token_888");

        // 2. 分账关系批量查询 batchquery
        server.enqueue(jsonMock(buildSignedResponse("alipay_trade_royalty_relation_batchquery_response",
                "{\"code\":\"10000\",\"msg\":\"Success\",\"result_code\":\"SUCCESS\",\"current_page_num\":1,\"current_page_size\":20,\"total_page_num\":1,\"total_record_num\":1,\"receiver_list\":[{\"type\":\"userId\",\"account\":\"2088101126708402\",\"name\":\"合作方A\"}]}",
                alipayPrivateKey)));
        AlipayTradeRoyaltyRelationBatchqueryModel batchModel = new AlipayTradeRoyaltyRelationBatchqueryModel();
        batchModel.setOutRequestNo("QUERY_REL_001");
        batchModel.setPageNum(1L);
        batchModel.setPageSize(20L);
        var batchResp = profitSharingService.batchQueryRelation(batchModel);
        assertEquals(1, batchResp.getReceiverList().size());
        assertRequestCarriesTokenAndMethod(server.takeRequest(), "alipay.trade.royalty.relation.batchquery", "isv_royalty_token_888");

        // 3. 交易结算确认 settle.confirm
        server.enqueue(jsonMock(buildSignedResponse("alipay_trade_settle_confirm_response",
                "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"202611180099\",\"out_request_no\":\"CONF_001\",\"settle_amount\":\"99.00\"}",
                alipayPrivateKey)));
        AlipayTradeSettleConfirmModel confirmModel = new AlipayTradeSettleConfirmModel();
        confirmModel.setOutRequestNo("CONF_001");
        confirmModel.setTradeNo("202611180099");
        confirmModel.setSettleInfo(settleInfo);
        var confirmResp = profitSharingService.confirmSettle(confirmModel);
        assertEquals("99.00", confirmResp.getSettleAmount());
        assertRequestCarriesTokenAndMethod(server.takeRequest(), "alipay.trade.settle.confirm", "isv_royalty_token_888");

        // 4. 统一收单交易分账结算 order.settle
        server.enqueue(jsonMock(buildSignedResponse("alipay_trade_order_settle_response",
                "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"202611180099\",\"settle_no\":\"20261118SETTLE01\"}",
                alipayPrivateKey)));
        OpenApiRoyaltyDetailInfoPojo detail = new OpenApiRoyaltyDetailInfoPojo();
        detail.setTransInType("userId");
        detail.setTransIn("2088101126708402");
        detail.setAmount("19.80");
        AlipayTradeOrderSettleModel settleModel = new AlipayTradeOrderSettleModel();
        settleModel.setOutRequestNo("SETTLE_001");
        settleModel.setTradeNo("202611180099");
        settleModel.setRoyaltyParameters(List.of(detail));
        var settleResp = profitSharingService.orderSettle(settleModel);
        assertEquals("20261118SETTLE01", settleResp.getSettleNo());
        assertRequestCarriesTokenAndMethod(server.takeRequest(), "alipay.trade.order.settle", "isv_royalty_token_888");

        // 5. 交易分账查询 order.settle.query
        server.enqueue(jsonMock(buildSignedResponse("alipay_trade_order_settle_query_response",
                "{\"code\":\"10000\",\"msg\":\"Success\",\"out_request_no\":\"SETTLE_001\",\"royalty_detail_list\":[{\"amount\":\"19.80\",\"state\":\"SUCCESS\"}]}",
                alipayPrivateKey)));
        AlipayTradeOrderSettleQueryModel queryModel = new AlipayTradeOrderSettleQueryModel();
        queryModel.setSettleNo("20261118SETTLE01");
        var queryResp = profitSharingService.queryOrderSettle(queryModel);
        assertEquals("SETTLE_001", queryResp.getOutRequestNo());
        assertRequestCarriesTokenAndMethod(server.takeRequest(), "alipay.trade.order.settle.query", "isv_royalty_token_888");

        // 6. 分账关系解绑 unbind
        server.enqueue(jsonMock(buildSignedResponse("alipay_trade_royalty_relation_unbind_response",
                "{\"code\":\"10000\",\"msg\":\"Success\",\"result_code\":\"SUCCESS\"}", alipayPrivateKey)));
        AlipayTradeRoyaltyRelationUnbindModel unbindModel = new AlipayTradeRoyaltyRelationUnbindModel();
        unbindModel.setOutRequestNo("UNBIND_001");
        unbindModel.setReceiverList(List.of(receiver));
        assertEquals("SUCCESS", profitSharingService.unbindRelation(unbindModel).getResultCode());
        assertRequestCarriesTokenAndMethod(server.takeRequest(), "alipay.trade.royalty.relation.unbind", "isv_royalty_token_888");
    }

    @Test
    @DisplayName("前置冻结扩展全渠道覆写与显式 appAuthToken/异常分支校验")
    void testCheckoutPreFreezeOverloadsAndErrorBranches() throws Exception {
        alipayTemplate.setProfitSharingEnabled(true);

        // 1. payJsapi 携带前置冻结参数
        server.enqueue(jsonMock(buildSignedResponse("alipay_trade_create_response",
                "{\"code\":\"10000\",\"msg\":\"Success\",\"out_trade_no\":\"JSAPI_FRZ_01\",\"trade_no\":\"202611180077\"}",
                alipayPrivateKey)));
        PayResponse jsapiResp = alipayTemplate.payJsapi(AlipayPayRequest.builder()
                .outTradeNo("JSAPI_FRZ_01")
                .subject("小程序分账前置冻结")
                .amount(new BigDecimal("66.00"))
                .buyerId("2088101126708402")
                .royaltyFreeze(true)
                .build());
        assertTrue(jsapiResp.isSuccess());
        String jsapiBody = URLDecoder.decode(server.takeRequest().getBody().readUtf8(), StandardCharsets.UTF_8);
        assertTrue(jsapiBody.contains("\"royalty_freeze\":\"true\""));

        // 2. 显式传入 appKey + appAuthToken 覆盖应用默认令牌
        server.enqueue(jsonMock(buildSignedResponse("alipay_trade_order_settle_response",
                "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"202611180077\",\"settle_no\":\"S_OVERRIDE_01\"}",
                alipayPrivateKey)));
        AlipayTradeOrderSettleModel settleModel = new AlipayTradeOrderSettleModel();
        settleModel.setOutRequestNo("OVR_001");
        settleModel.setTradeNo("202611180077");
        var ovrResp = profitSharingService.orderSettle("isv", "explicit_override_token_999", settleModel);
        assertEquals("S_OVERRIDE_01", ovrResp.getSettleNo());
        assertRequestCarriesTokenAndMethod(server.takeRequest(), "alipay.trade.order.settle", "explicit_override_token_999");

        // 3. 分账业务失败应精准抛出 PayTradeException，HTTP 500 抛出 PayNetworkException
        server.enqueue(jsonMock(buildSignedResponse("alipay_trade_order_settle_response",
                "{\"code\":\"40004\",\"msg\":\"Business Failed\",\"sub_code\":\"ACQ.ILLEGAL_SETTLE_STATE\",\"sub_msg\":\"分账状态非法\"}",
                alipayPrivateKey)));
        var bizEx = assertThrows(io.github.atengk.boot.pay.exception.PayTradeException.class,
                () -> profitSharingService.orderSettle(settleModel));
        assertEquals("ACQ.ILLEGAL_SETTLE_STATE", bizEx.getRawErrorCode());

        server.enqueue(new MockResponse().setResponseCode(500).setBody("Gateway Unavailable"));
        assertThrows(io.github.atengk.boot.pay.exception.PayNetworkException.class,
                () -> profitSharingService.orderSettle(settleModel));
    }

    private static MockResponse jsonMock(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=utf-8")
                .setBody(body);
    }

    private static void assertRequestCarriesTokenAndMethod(RecordedRequest recorded, String expectedMethod, String expectedToken) {
        String url = recorded.getRequestUrl() != null ? recorded.getRequestUrl().toString() : "";
        String body = URLDecoder.decode(recorded.getBody().readUtf8(), StandardCharsets.UTF_8);
        assertTrue(url.contains(expectedMethod) || body.contains(expectedMethod), "缺少接口方法: " + expectedMethod);
        assertTrue(url.contains(expectedToken) || body.contains(expectedToken), "缺少 app_auth_token: " + expectedToken);
    }

    private static String buildSignedResponse(String responseNodeName, String nodeJson, PrivateKey privateKey) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(nodeJson.getBytes(StandardCharsets.UTF_8));
        String sign = Base64.getEncoder().encodeToString(signature.sign());
        return "{\"" + responseNodeName + "\":" + nodeJson + ",\"sign\":\"" + sign + "\"}";
    }
}
