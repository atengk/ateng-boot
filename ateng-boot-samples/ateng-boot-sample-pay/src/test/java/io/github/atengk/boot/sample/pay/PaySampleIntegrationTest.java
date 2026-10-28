package io.github.atengk.boot.sample.pay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.pay.java.service.profitsharing.model.AddReceiverResponse;
import com.wechat.pay.java.service.profitsharing.model.OrdersEntity;
import com.wechat.pay.java.service.profitsharing.model.ReceiverType;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.pay.core.PayTemplate;
import io.github.atengk.boot.pay.core.executor.BarcodePayExecutor;
import io.github.atengk.boot.pay.core.profitsharing.WechatProfitSharingService;
import io.github.atengk.boot.pay.enums.PayChannel;
import io.github.atengk.boot.pay.enums.PayRefundState;
import io.github.atengk.boot.pay.enums.PayTradeState;
import io.github.atengk.boot.pay.enums.PayTradeType;
import io.github.atengk.boot.pay.enums.PayTransferState;
import io.github.atengk.boot.pay.model.callback.PayCallbackResult;
import io.github.atengk.boot.pay.model.callback.PaymentSuccessCallback;
import io.github.atengk.boot.pay.model.callback.RefundSuccessCallback;
import io.github.atengk.boot.pay.model.response.PayRefundResponse;
import io.github.atengk.boot.pay.model.response.PayResponse;
import io.github.atengk.boot.pay.model.response.PayTransferResponse;
import io.github.atengk.boot.pay.model.response.WechatJsapiParams;
import io.github.atengk.boot.sample.pay.dto.*;
import io.github.atengk.boot.sample.pay.listener.OrderPaymentCallbackListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 支付场景端到端真实集成测试
 * <p>
 * 基于 MockMvc 与全内存离线桩，端到端验证：
 * 1. Native / JSAPI / H5 / App 收银台下单接口契约与扁平凭据返回；
 * 2. 订单主动查单与关单接口契约；
 * 3. 第一道防线 JSR-303 参数校验与快速失败；
 * 4. 业务支付成功回调监听器 (PayCallbackListener SPI) 的幂等落地；
 * 5. 统一 Webhook 网关端点 (/ateng-pay/callback/{channel}) 路由与渠道 ACK 应答。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("多渠道支付场景端到端集成测试")
class PaySampleIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderPaymentCallbackListener callbackListener;

    @MockBean
    private PayTemplate payTemplate;

    @MockBean
    private BarcodePayExecutor barcodePayExecutor;

    @MockBean
    private WechatProfitSharingService profitSharingService;

    @BeforeEach
    @AfterEach
    void cleanContext() {
        UserContextHolder.clear();
        MDC.clear();
        if (callbackListener != null) {
            callbackListener.reset();
        }
    }

    private static final String MOCK_WECHAT_BILL_CSV = """
            交易时间,公众账号ID,商户号,特约商户号,设备号,微信订单号,商户订单号,用户标识,交易类型,交易状态,付款银行,货币种类,应结订单金额,代金券金额,微信退款单号,商户退款单号,退款金额,充值券退款金额,退款类型,退款状态,商品名称,商户数据包,手续费,费率,订单金额,申请退款金额,费率备注
            `2026-10-24 09:15:00,`wx8888888888888888,`1900000109,`0,`, `4200000000202610240000000001,`WX_OUT_001,`oUpF8uMuAJO_M2pxb1Q9zNjWeS6o,`JSAPI,`SUCCESS,`CMB_CREDIT,`CNY,`100.00,`0.00,`0,`0,`0.00,`0.00,`,`, `测试商品A,`, `0.60,`0.60%,`100.00,`0.00,`
            `2026-10-24 10:30:00,`wx8888888888888888,`1900000109,`0,`, `4200000000202610240000000002,`WX_OUT_002,`oUpF8uMuAJO_M2pxb1Q9zNjWeS6o,`NATIVE,`SUCCESS,`ICBC_DEBIT,`CNY,`50.00,`0.00,`0,`0,`0.00,`0.00,`,`, `测试商品B,`, `0.30,`0.60%,`50.00,`0.00,`
            总交易单数,应结订单总金额,退款总金额,充值券退款总金额,手续费总金额,订单总金额,申请退款总金额
            `2,`150.00,`0.00,`0.00,`0.90,`150.00,`0.00
            """;

    private InputStream createMockWechatGzipBillStream() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
                gzos.write(MOCK_WECHAT_BILL_CSV.getBytes(StandardCharsets.UTF_8));
            }
            return new ByteArrayInputStream(baos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException("生成 Mock GZIP 对账单失败", e);
        }
    }

    @BeforeEach
    void setUpMocks() {
        // 1. Native 扫码桩
        when(payTemplate.pay(argThat(req -> req != null && req.getTradeType() == PayTradeType.NATIVE)))
                .thenAnswer(inv -> {
                    var req = inv.getArgument(0, io.github.atengk.boot.pay.model.request.UnifiedPayRequest.class);
                    return PayResponse.builder()
                            .channel(req.getChannel())
                            .tradeType(PayTradeType.NATIVE)
                            .outTradeNo(req.getOutTradeNo())
                            .amount(req.getAmount())
                            .success(true)
                            .qrCode("weixin://wxpay/bizpayurl?pr=mockQrCode999")
                            .build();
                });

        // 2. JSAPI 小程序/公众号调起桩 (含服务端全自动二次加签 5 元素)
        when(payTemplate.pay(argThat(req -> req != null && req.getTradeType() == PayTradeType.JSAPI)))
                .thenAnswer(inv -> {
                    var req = inv.getArgument(0, io.github.atengk.boot.pay.model.request.UnifiedPayRequest.class);
                    return PayResponse.builder()
                            .channel(req.getChannel())
                            .tradeType(PayTradeType.JSAPI)
                            .outTradeNo(req.getOutTradeNo())
                            .amount(req.getAmount())
                            .prepayId("wx20261023prepay001")
                            .success(true)
                            .wechatJsapi(WechatJsapiParams.builder()
                                    .appId("wx8888888888888888")
                                    .timeStamp("1729699200")
                                    .nonceStr("mockNonceStr123")
                                    .packageVal("prepay_id=wx20261023prepay001")
                                    .signType("RSA")
                                    .paySign("MOCK_PAY_SIGN_BASE64_VALUE")
                                    .build())
                            .build();
                });

        // 3. H5 移动网页支付桩
        when(payTemplate.pay(argThat(req -> req != null && req.getTradeType() == PayTradeType.H5)))
                .thenAnswer(inv -> {
                    var req = inv.getArgument(0, io.github.atengk.boot.pay.model.request.UnifiedPayRequest.class);
                    return PayResponse.builder()
                            .channel(req.getChannel())
                            .tradeType(PayTradeType.H5)
                            .outTradeNo(req.getOutTradeNo())
                            .amount(req.getAmount())
                            .success(true)
                            .payUrl("https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?prepay_id=mockPrepay003")
                            .build();
                });

        // 4. App 客户端支付桩
        when(payTemplate.pay(argThat(req -> req != null && req.getTradeType() == PayTradeType.APP)))
                .thenAnswer(inv -> {
                    var req = inv.getArgument(0, io.github.atengk.boot.pay.model.request.UnifiedPayRequest.class);
                    return PayResponse.builder()
                            .channel(req.getChannel())
                            .tradeType(PayTradeType.APP)
                            .outTradeNo(req.getOutTradeNo())
                            .amount(req.getAmount())
                            .success(true)
                            .orderString("alipay_sdk=alipay-sdk-java-4.40.1044&biz_content=mock_content")
                            .build();
                });

        // 5. 订单主动查询桩
        when(payTemplate.queryOrder(any(io.github.atengk.boot.pay.model.request.UnifiedQueryRequest.class)))
                .thenAnswer(inv -> {
                    var req = inv.getArgument(0, io.github.atengk.boot.pay.model.request.UnifiedQueryRequest.class);
                    return PayResponse.builder()
                            .channel(req.getChannel())
                            .outTradeNo(req.getOutTradeNo())
                            .transactionId("TRANSACTION_20261023_001")
                            .amount(new BigDecimal("99.90"))
                            .tradeState(PayTradeState.SUCCESS)
                            .rawTradeState("SUCCESS")
                            .tradeStateDesc("支付成功")
                            .success(true)
                            .build();
                });

        // 6. 订单主动关闭桩
        when(payTemplate.closeOrder(any(io.github.atengk.boot.pay.model.request.UnifiedCloseRequest.class)))
                .thenAnswer(inv -> {
                    var req = inv.getArgument(0, io.github.atengk.boot.pay.model.request.UnifiedCloseRequest.class);
                    return PayResponse.builder()
                            .channel(req.getChannel())
                            .outTradeNo(req.getOutTradeNo())
                            .tradeState(PayTradeState.CLOSED)
                            .rawTradeState("CLOSED")
                            .tradeStateDesc("订单已关闭")
                            .success(true)
                            .build();
                });

        // 7. 发起退款桩
        when(payTemplate.refund(any(io.github.atengk.boot.pay.model.request.UnifiedRefundRequest.class)))
                .thenAnswer(inv -> {
                    var req = inv.getArgument(0, io.github.atengk.boot.pay.model.request.UnifiedRefundRequest.class);
                    return PayRefundResponse.builder()
                            .channel(req.getChannel())
                            .outTradeNo(req.getOutTradeNo())
                            .outRefundNo(req.getOutRefundNo())
                            .refundId("REFUND_TX_20261024_001")
                            .refundAmount(req.getRefundAmount())
                            .totalAmount(req.getTotalAmount())
                            .refundState(PayRefundState.PROCESSING)
                            .rawRefundState("PROCESSING")
                            .success(true)
                            .build();
                });

        // 8. 查询退款桩
        when(payTemplate.queryRefund(any(io.github.atengk.boot.pay.model.request.UnifiedRefundQueryRequest.class)))
                .thenAnswer(inv -> {
                    var req = inv.getArgument(0, io.github.atengk.boot.pay.model.request.UnifiedRefundQueryRequest.class);
                    return PayRefundResponse.builder()
                            .channel(req.getChannel())
                            .outTradeNo(req.getOutTradeNo())
                            .outRefundNo(req.getOutRefundNo())
                            .refundId("REFUND_TX_20261024_001")
                            .refundAmount(new BigDecimal("20.00"))
                            .totalAmount(new BigDecimal("100.00"))
                            .refundState(PayRefundState.SUCCESS)
                            .rawRefundState("SUCCESS")
                            .success(true)
                            .build();
                });

        // 9. 发起单笔转账桩
        when(payTemplate.transfer(any(io.github.atengk.boot.pay.model.request.UnifiedTransferRequest.class)))
                .thenAnswer(inv -> {
                    var req = inv.getArgument(0, io.github.atengk.boot.pay.model.request.UnifiedTransferRequest.class);
                    return PayTransferResponse.builder()
                            .channel(req.getChannel())
                            .outBizNo(req.getOutBizNo())
                            .orderId("TRANSFER_TX_20261024_001")
                            .amount(req.getAmount())
                            .status(PayTransferState.SUCCESS)
                            .payTime("2026-10-24 15:30:00")
                            .build();
                });

        // 10. 查询单笔转账桩
        when(payTemplate.queryTransfer(any(io.github.atengk.boot.pay.model.request.UnifiedTransferQueryRequest.class)))
                .thenAnswer(inv -> {
                    var req = inv.getArgument(0, io.github.atengk.boot.pay.model.request.UnifiedTransferQueryRequest.class);
                    return PayTransferResponse.builder()
                            .channel(req.getChannel())
                            .outBizNo(req.getOutBizNo())
                            .orderId(req.getOrderId() != null ? req.getOrderId() : "TRANSFER_TX_20261024_001")
                            .amount(new BigDecimal("50.00"))
                            .status(PayTransferState.SUCCESS)
                            .build();
                });

        // 11. 线下条码支付轮询执行器桩
        when(barcodePayExecutor.execute(any(io.github.atengk.boot.pay.model.request.UnifiedPayRequest.class)))
                .thenAnswer(inv -> {
                    var req = inv.getArgument(0, io.github.atengk.boot.pay.model.request.UnifiedPayRequest.class);
                    return PayResponse.builder()
                            .channel(req.getChannel())
                            .tradeType(PayTradeType.BARCODE)
                            .outTradeNo(req.getOutTradeNo())
                            .amount(req.getAmount())
                            .tradeState(PayTradeState.SUCCESS)
                            .rawTradeState("SUCCESS")
                            .success(true)
                            .build();
                });

        // 12. 账单流下载桩 (返回 GZIP 压缩流)
        when(payTemplate.downloadBillStream(any(io.github.atengk.boot.pay.model.bill.PayBillDownloadRequest.class)))
                .thenAnswer(inv -> createMockWechatGzipBillStream());

        // 13. 微信分账桩
        AddReceiverResponse mockAddReceiverResp = new AddReceiverResponse();
        mockAddReceiverResp.setType(ReceiverType.PERSONAL_OPENID);
        mockAddReceiverResp.setAccount("oUpF8uMuAJO_M2pxb1Q9zNjWeS6o");
        when(profitSharingService.addReceiver(any(), any())).thenReturn(mockAddReceiverResp);

        OrdersEntity mockOrder = new OrdersEntity();
        mockOrder.setOutOrderNo("ORD_UNFREEZE_001");
        mockOrder.setOrderId("3000002000202610240001");
        when(profitSharingService.unfreezeOrder(any(), any())).thenReturn(mockOrder);
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

        mockMvc.perform(post("/pay/checkout/native")
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
    @DisplayName("2. JSAPI 调起收单：全自动生成二次加签 5 元素调起凭据包")
    void shouldReturnWechatJsapiParamsForJsapiCheckout() throws Exception {
        PayCheckoutDTO dto = PayCheckoutDTO.builder()
                .channel(PayChannel.WECHAT_PAY)
                .outTradeNo("ORD_JSAPI_20261023_002")
                .amount(new BigDecimal("12.50"))
                .subject("咖啡订单在线支付")
                .openid("oUpF8uMuAJO_M2pxb1Q9zNjWeS6o")
                .build();

        mockMvc.perform(post("/pay/checkout/jsapi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tradeType").value("JSAPI"))
                .andExpect(jsonPath("$.data.wechatJsapi.appId").value("wx8888888888888888"))
                .andExpect(jsonPath("$.data.wechatJsapi.timeStamp").value("1729699200"))
                .andExpect(jsonPath("$.data.wechatJsapi.nonceStr").value("mockNonceStr123"))
                .andExpect(jsonPath("$.data.wechatJsapi.package").value("prepay_id=wx20261023prepay001"))
                .andExpect(jsonPath("$.data.wechatJsapi.signType").value("RSA"))
                .andExpect(jsonPath("$.data.wechatJsapi.paySign").value("MOCK_PAY_SIGN_BASE64_VALUE"));
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

        mockMvc.perform(post("/pay/checkout/h5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tradeType").value("H5"))
                .andExpect(jsonPath("$.data.payUrl").value("https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?prepay_id=mockPrepay003"));
    }

    @Test
    @DisplayName("4. App 移动收单：成功返回加签 orderString")
    void shouldReturnOrderStringForAppCheckout() throws Exception {
        PayCheckoutDTO dto = PayCheckoutDTO.builder()
                .channel(PayChannel.ALIPAY)
                .outTradeNo("ORD_APP_20261023_004")
                .amount(new BigDecimal("199.00"))
                .subject("App应用内消费")
                .build();

        mockMvc.perform(post("/pay/checkout/app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tradeType").value("APP"))
                .andExpect(jsonPath("$.data.orderString").value("alipay_sdk=alipay-sdk-java-4.40.1044&biz_content=mock_content"));
    }

    @Test
    @DisplayName("5. 主动查单：返回强类型支付状态与流水号")
    void shouldReturnQueryOrderResult() throws Exception {
        PayOrderQueryDTO dto = PayOrderQueryDTO.builder()
                .channel(PayChannel.WECHAT_PAY)
                .outTradeNo("ORD_QUERY_20261023_005")
                .build();

        mockMvc.perform(post("/pay/order/query")
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

        mockMvc.perform(post("/pay/order/close")
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

        mockMvc.perform(post("/pay/checkout/native")
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

        mockMvc.perform(post("/pay/checkout/native")
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
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("fail"));
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

        mockMvc.perform(post("/pay/refund/apply")
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

        mockMvc.perform(post("/pay/refund/query")
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

        mockMvc.perform(post("/pay/transfer/apply")
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

        mockMvc.perform(post("/pay/transfer/query")
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

        mockMvc.perform(post("/pay/barcode/cashier")
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

        mockMvc.perform(post("/pay/bill/parse-memory")
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

        mockMvc.perform(post("/pay/bill/stream-reconcile")
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
                .name("李四")
                .relationType("USER")
                .build();

        mockMvc.perform(post("/pay/profit-sharing/receiver/add")
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

        mockMvc.perform(post("/pay/profit-sharing/order/unfreeze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(unfreezeDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.outOrderNo").value("ORD_UNFREEZE_001"));
    }
}
