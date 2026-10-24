package io.github.atengk.boot.pay.core.transfer;

import io.github.atengk.boot.pay.core.AlipayTemplate;
import io.github.atengk.boot.pay.core.PayTemplate;
import io.github.atengk.boot.pay.core.WechatPayTemplate;
import io.github.atengk.boot.pay.enums.PayChannel;
import io.github.atengk.boot.pay.enums.PayResultCode;
import io.github.atengk.boot.pay.enums.PayTransferState;
import io.github.atengk.boot.pay.exception.PayException;
import io.github.atengk.boot.pay.model.request.AlipayTransferRequest;
import io.github.atengk.boot.pay.model.request.UnifiedTransferQueryRequest;
import io.github.atengk.boot.pay.model.request.UnifiedTransferRequest;
import io.github.atengk.boot.pay.model.request.WechatTransferRequest;
import io.github.atengk.boot.pay.model.response.PayTransferResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单笔资金转账业务门面服务单元测试
 *
 * @author Ateng
 * @since 2026-10-24
 */
class PayTransferServiceTest {

    @Test
    @DisplayName("PayTransferService: 顶层统一转账与查询正确路由委派至 PayTemplate")
    void testUnifiedTransferDelegation() {
        PayTemplate payTemplate = Mockito.mock(PayTemplate.class);
        PayTransferService service = new PayTransferService(payTemplate);

        PayTransferResponse mockResponse = PayTransferResponse.builder()
                .channel(PayChannel.WECHAT_PAY)
                .outBizNo("TRANS_TEST_001")
                .status(PayTransferState.SUCCESS)
                .amount(new BigDecimal("10.00"))
                .build();

        when(payTemplate.transfer(any(UnifiedTransferRequest.class))).thenReturn(mockResponse);
        when(payTemplate.queryTransfer(any(UnifiedTransferQueryRequest.class))).thenReturn(mockResponse);
        when(payTemplate.queryTransfer(PayChannel.WECHAT_PAY, "TRANS_TEST_001", "ORDER_999")).thenReturn(mockResponse);

        UnifiedTransferRequest req = UnifiedTransferRequest.builder()
                .channel(PayChannel.WECHAT_PAY)
                .outBizNo("TRANS_TEST_001")
                .amount(new BigDecimal("10.00"))
                .build();

        PayTransferResponse res1 = service.transfer(req);
        assertEquals("TRANS_TEST_001", res1.getOutBizNo());
        verify(payTemplate).transfer(req);

        UnifiedTransferQueryRequest queryReq = UnifiedTransferQueryRequest.builder()
                .channel(PayChannel.WECHAT_PAY)
                .outBizNo("TRANS_TEST_001")
                .build();
        PayTransferResponse res2 = service.queryTransfer(queryReq);
        assertEquals("TRANS_TEST_001", res2.getOutBizNo());
        verify(payTemplate).queryTransfer(queryReq);

        PayTransferResponse res3 = service.queryTransfer(PayChannel.WECHAT_PAY, "TRANS_TEST_001", "ORDER_999");
        assertEquals("TRANS_TEST_001", res3.getOutBizNo());
        verify(payTemplate).queryTransfer(PayChannel.WECHAT_PAY, "TRANS_TEST_001", "ORDER_999");
    }

    @Test
    @DisplayName("PayTransferService: 渠道模块未注入时抛出规范定义的 PayException(PAY_GATEWAY_ERROR)")
    void testChannelTemplateNotPresentThrowsPayException() {
        PayTemplate payTemplate = Mockito.mock(PayTemplate.class);
        when(payTemplate.getWechatPayTemplate()).thenReturn(null);
        when(payTemplate.getAlipayTemplate()).thenReturn(null);

        PayTransferService service = new PayTransferService(payTemplate);

        WechatTransferRequest wechatReq = WechatTransferRequest.builder()
                .outBatchNo("BATCH_001")
                .outDetailNo("DETAIL_001")
                .amount(new BigDecimal("5.00"))
                .build();

        PayException ex1 = assertThrows(PayException.class, () -> service.transferWechat(wechatReq));
        assertEquals(PayResultCode.PAY_GATEWAY_ERROR, ex1.getResultCode());
        assertTrue(ex1.getMessage().contains("微信支付模块未装配或未启用"));

        PayException ex2 = assertThrows(PayException.class,
                () -> service.queryWechatTransfer("default", "BATCH_001", "DETAIL_001"));
        assertEquals(PayResultCode.PAY_GATEWAY_ERROR, ex2.getResultCode());

        AlipayTransferRequest alipayReq = AlipayTransferRequest.builder()
                .outBizNo("ALI_001")
                .amount(new BigDecimal("5.00"))
                .build();

        PayException ex3 = assertThrows(PayException.class, () -> service.transferAlipay(alipayReq));
        assertEquals(PayResultCode.PAY_GATEWAY_ERROR, ex3.getResultCode());
        assertTrue(ex3.getMessage().contains("支付宝模块未装配或未启用"));

        PayException ex4 = assertThrows(PayException.class,
                () -> service.queryAlipayTransfer("default", "ALI_001", "ORDER_001"));
        assertEquals(PayResultCode.PAY_GATEWAY_ERROR, ex4.getResultCode());
    }

    @Test
    @DisplayName("PayTransferService: 渠道模块就绪时直达方法正确委派")
    void testChannelTemplatePresentDelegatesProperly() {
        PayTemplate payTemplate = Mockito.mock(PayTemplate.class);
        WechatPayTemplate wechatTemplate = Mockito.mock(WechatPayTemplate.class);
        AlipayTemplate alipayTemplate = Mockito.mock(AlipayTemplate.class);

        when(payTemplate.getWechatPayTemplate()).thenReturn(wechatTemplate);
        when(payTemplate.getAlipayTemplate()).thenReturn(alipayTemplate);

        PayTransferService service = new PayTransferService(payTemplate);

        PayTransferResponse wechatResp = PayTransferResponse.builder()
                .channel(PayChannel.WECHAT_PAY)
                .outBizNo("BATCH_002")
                .status(PayTransferState.PROCESSING)
                .build();
        when(wechatTemplate.transfer(any())).thenReturn(wechatResp);
        when(wechatTemplate.queryTransferDetail("mch", "BATCH_002", "DET_002")).thenReturn(wechatResp);

        WechatTransferRequest wechatReq = WechatTransferRequest.builder()
                .outBatchNo("BATCH_002")
                .outDetailNo("DET_002")
                .amount(new BigDecimal("2.00"))
                .build();
        assertEquals(wechatResp, service.transferWechat(wechatReq));
        assertEquals(wechatResp, service.queryWechatTransfer("mch", "BATCH_002", "DET_002"));

        PayTransferResponse alipayResp = PayTransferResponse.builder()
                .channel(PayChannel.ALIPAY)
                .outBizNo("ALI_002")
                .status(PayTransferState.SUCCESS)
                .build();
        when(alipayTemplate.transfer(any())).thenReturn(alipayResp);
        when(alipayTemplate.queryTransfer("app", "ALI_002", "ORD_002")).thenReturn(alipayResp);

        AlipayTransferRequest alipayReq = AlipayTransferRequest.builder()
                .outBizNo("ALI_002")
                .amount(new BigDecimal("2.00"))
                .build();
        assertEquals(alipayResp, service.transferAlipay(alipayReq));
        assertEquals(alipayResp, service.queryAlipayTransfer("app", "ALI_002", "ORD_002"));
    }
}
