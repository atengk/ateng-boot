package io.github.atengk.boot.pay.core.profitsharing;

import com.wechat.pay.java.service.profitsharing.ProfitsharingService;
import com.wechat.pay.java.service.profitsharing.model.*;
import io.github.atengk.boot.pay.core.WechatPayTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 微信分账专有业务服务门面 WechatProfitSharingService 委托单测
 *
 * @author Ateng
 * @since 2026-10-24
 */
class WechatProfitSharingServiceTest {

    private WechatPayTemplate wechatPayTemplate;
    private WechatProfitSharingService profitSharingService;

    @BeforeEach
    void setUp() {
        wechatPayTemplate = Mockito.mock(WechatPayTemplate.class);
        profitSharingService = new WechatProfitSharingService(wechatPayTemplate);
    }

    @Test
    @DisplayName("addReceiver / deleteReceiver 委托调用验证")
    void testReceiverManagementDelegation() {
        AddReceiverRequest addReq = new AddReceiverRequest();
        when(wechatPayTemplate.addProfitSharingReceiver(addReq)).thenReturn(new AddReceiverResponse());
        when(wechatPayTemplate.addProfitSharingReceiver(eq("mch1"), any(AddReceiverRequest.class))).thenReturn(new AddReceiverResponse());

        assertNotNull(profitSharingService.addReceiver(addReq));
        verify(wechatPayTemplate).addProfitSharingReceiver(addReq);

        assertNotNull(profitSharingService.addReceiver("mch1", addReq));
        verify(wechatPayTemplate).addProfitSharingReceiver("mch1", addReq);

        DeleteReceiverRequest delReq = new DeleteReceiverRequest();
        when(wechatPayTemplate.deleteProfitSharingReceiver(delReq)).thenReturn(new DeleteReceiverResponse());
        when(wechatPayTemplate.deleteProfitSharingReceiver(eq("mch1"), any(DeleteReceiverRequest.class))).thenReturn(new DeleteReceiverResponse());

        assertNotNull(profitSharingService.deleteReceiver(delReq));
        verify(wechatPayTemplate).deleteProfitSharingReceiver(delReq);

        assertNotNull(profitSharingService.deleteReceiver("mch1", delReq));
        verify(wechatPayTemplate).deleteProfitSharingReceiver("mch1", delReq);
    }

    @Test
    @DisplayName("createOrder / queryOrder / unfreezeOrder 委托调用验证")
    void testOrderLifecycleDelegation() {
        CreateOrderRequest createReq = new CreateOrderRequest();
        when(wechatPayTemplate.createProfitSharing(createReq)).thenReturn(new OrdersEntity());
        when(wechatPayTemplate.createProfitSharing(eq("mch1"), any(CreateOrderRequest.class))).thenReturn(new OrdersEntity());

        assertNotNull(profitSharingService.createOrder(createReq));
        verify(wechatPayTemplate).createProfitSharing(createReq);

        assertNotNull(profitSharingService.createOrder("mch1", createReq));
        verify(wechatPayTemplate).createProfitSharing("mch1", createReq);

        QueryOrderRequest queryReq = new QueryOrderRequest();
        when(wechatPayTemplate.queryProfitSharing(queryReq)).thenReturn(new OrdersEntity());
        when(wechatPayTemplate.queryProfitSharing(eq("mch1"), any(QueryOrderRequest.class))).thenReturn(new OrdersEntity());
        when(wechatPayTemplate.queryProfitSharing("PS_001", "TX_001")).thenReturn(new OrdersEntity());
        when(wechatPayTemplate.queryProfitSharing("mch1", "PS_001", "TX_001")).thenReturn(new OrdersEntity());

        assertNotNull(profitSharingService.queryOrder(queryReq));
        assertNotNull(profitSharingService.queryOrder("mch1", queryReq));
        assertNotNull(profitSharingService.queryOrder("PS_001", "TX_001"));
        assertNotNull(profitSharingService.queryOrder("mch1", "PS_001", "TX_001"));

        UnfreezeOrderRequest unfreezeReq = new UnfreezeOrderRequest();
        when(wechatPayTemplate.unfreezeProfitSharing(unfreezeReq)).thenReturn(new OrdersEntity());
        when(wechatPayTemplate.unfreezeProfitSharing(eq("mch1"), any(UnfreezeOrderRequest.class))).thenReturn(new OrdersEntity());
        when(wechatPayTemplate.unfreezeProfitSharing("PS_001", "TX_001", "解冻")).thenReturn(new OrdersEntity());
        when(wechatPayTemplate.unfreezeProfitSharing("mch1", "PS_001", "TX_001", "解冻")).thenReturn(new OrdersEntity());

        assertNotNull(profitSharingService.unfreezeOrder(unfreezeReq));
        assertNotNull(profitSharingService.unfreezeOrder("mch1", unfreezeReq));
        assertNotNull(profitSharingService.unfreezeOrder("PS_001", "TX_001", "解冻"));
        assertNotNull(profitSharingService.unfreezeOrder("mch1", "PS_001", "TX_001", "解冻"));
    }

    @Test
    @DisplayName("returnOrder / queryReturnOrder / queryOrderAmount / queryMerchantRatio 委托调用验证")
    void testReturnAndQueryDelegation() {
        CreateReturnOrderRequest returnReq = new CreateReturnOrderRequest();
        when(wechatPayTemplate.returnProfitSharing(returnReq)).thenReturn(new ReturnOrdersEntity());
        when(wechatPayTemplate.returnProfitSharing(eq("mch1"), any(CreateReturnOrderRequest.class))).thenReturn(new ReturnOrdersEntity());

        assertNotNull(profitSharingService.createReturnOrder(returnReq));
        assertNotNull(profitSharingService.createReturnOrder("mch1", returnReq));

        QueryReturnOrderRequest queryReturnReq = new QueryReturnOrderRequest();
        when(wechatPayTemplate.queryProfitSharingReturn(queryReturnReq)).thenReturn(new ReturnOrdersEntity());
        when(wechatPayTemplate.queryProfitSharingReturn(eq("mch1"), any(QueryReturnOrderRequest.class))).thenReturn(new ReturnOrdersEntity());
        when(wechatPayTemplate.queryProfitSharingReturn("RET_001", "PS_001")).thenReturn(new ReturnOrdersEntity());
        when(wechatPayTemplate.queryProfitSharingReturn("mch1", "RET_001", "PS_001")).thenReturn(new ReturnOrdersEntity());

        assertNotNull(profitSharingService.queryReturnOrder(queryReturnReq));
        assertNotNull(profitSharingService.queryReturnOrder("mch1", queryReturnReq));
        assertNotNull(profitSharingService.queryReturnOrder("RET_001", "PS_001"));
        assertNotNull(profitSharingService.queryReturnOrder("mch1", "RET_001", "PS_001"));

        QueryOrderAmountRequest amountReq = new QueryOrderAmountRequest();
        when(wechatPayTemplate.queryProfitSharingAmount(amountReq)).thenReturn(new QueryOrderAmountResponse());
        when(wechatPayTemplate.queryProfitSharingAmount(eq("mch1"), any(QueryOrderAmountRequest.class))).thenReturn(new QueryOrderAmountResponse());

        assertNotNull(profitSharingService.queryOrderAmount(amountReq));
        assertNotNull(profitSharingService.queryOrderAmount("mch1", amountReq));

        QueryMerchantRatioRequest ratioReq = new QueryMerchantRatioRequest();
        when(wechatPayTemplate.queryProfitSharingMerchantRatio(ratioReq)).thenReturn(new QueryMerchantRatioResponse());
        when(wechatPayTemplate.queryProfitSharingMerchantRatio(eq("mch1"), any(QueryMerchantRatioRequest.class))).thenReturn(new QueryMerchantRatioResponse());

        assertNotNull(profitSharingService.queryMerchantRatio(ratioReq));
        assertNotNull(profitSharingService.queryMerchantRatio("mch1", ratioReq));

        ProfitsharingService mockEscape = Mockito.mock(ProfitsharingService.class);
        when(wechatPayTemplate.getProfitsharingService()).thenReturn(mockEscape);
        when(wechatPayTemplate.getProfitsharingService("mch1")).thenReturn(mockEscape);

        assertNotNull(profitSharingService.getProfitsharingService());
        assertNotNull(profitSharingService.getProfitsharingService("mch1"));
    }
}
