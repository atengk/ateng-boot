package io.github.atengk.boot.pay.core.executor;

import io.github.atengk.boot.pay.config.properties.BarcodePayProperties;
import io.github.atengk.boot.pay.core.PayTemplate;
import io.github.atengk.boot.pay.enums.PayChannel;
import io.github.atengk.boot.pay.enums.PayResultCode;
import io.github.atengk.boot.pay.enums.PayTradeState;
import io.github.atengk.boot.pay.enums.PayTradeType;
import io.github.atengk.boot.pay.exception.PayException;
import io.github.atengk.boot.pay.exception.PayNetworkException;
import io.github.atengk.boot.pay.model.request.UnifiedCancelRequest;
import io.github.atengk.boot.pay.model.request.UnifiedPayRequest;
import io.github.atengk.boot.pay.model.request.UnifiedQueryRequest;
import io.github.atengk.boot.pay.model.response.PayResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 线下收银台条码支付执行器 BarcodePayExecutor 100% 离线自洽单元测试
 * <p>
 * 验证：
 * 1. 首次扣款直接成功即刻短路返回（零等待）；
 * 2. 等待输密进入阶梯式轮询并在中途成功时即刻短路返回；
 * 3. 超时未完成时自动触发底层主动撤销冲正 (cancelOrder) 并返回 REVOKED；
 * 4. 首次网络抖动异常时的容错与轮询恢复；
 * 5. 查单遇到不可逆终态 (CLOSED) 时的即刻终止与冲正；
 * 6. 参数前置防卫 Fail-Fast 校验。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-24
 */
@ExtendWith(MockitoExtension.class)
class BarcodePayExecutorTest {

    @Mock
    private PayTemplate payTemplate;

    private BarcodePayExecutor executor;
    private BarcodePayProperties properties;
    private AtomicInteger sleepCount;

    @BeforeEach
    void setUp() {
        properties = new BarcodePayProperties();
        properties.setMaxWaitTime(Duration.ofMillis(100));
        properties.setPollInterval(Duration.ofMillis(20));
        properties.setAutoCancelOnTimeout(true);

        executor = new BarcodePayExecutor(payTemplate, properties);
        sleepCount = new AtomicInteger(0);

        // 使用零物理等待的 Mock Sleeper 加速单测执行（毫秒级极速反馈）
        executor.setSleeper(duration -> sleepCount.incrementAndGet());
    }

    @Test
    @DisplayName("条码收银首次直接成功: 免密小额支付立即返回 SUCCESS，零轮询零撤销")
    void testExecute_immediateSuccess() {
        UnifiedPayRequest request = UnifiedPayRequest.builder()
                .channel(PayChannel.WECHAT_PAY)
                .outTradeNo("BAR_IMM_001")
                .amount(new BigDecimal("10.00"))
                .authCode("134567890123456789")
                .subject("便利店现磨咖啡")
                .build();

        PayResponse mockPayResp = PayResponse.builder()
                .channel(PayChannel.WECHAT_PAY)
                .tradeType(PayTradeType.BARCODE)
                .outTradeNo("BAR_IMM_001")
                .transactionId("TX_10001")
                .amount(new BigDecimal("10.00"))
                .tradeState(PayTradeState.SUCCESS)
                .success(true)
                .build();
        when(payTemplate.pay(any(UnifiedPayRequest.class))).thenReturn(mockPayResp);

        PayResponse response = executor.execute(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals(PayTradeState.SUCCESS, response.getTradeState());
        assertEquals("TX_10001", response.getTransactionId());

        // 验证没有发起任何查单与撤销
        verify(payTemplate, times(1)).pay(any(UnifiedPayRequest.class));
        verify(payTemplate, never()).queryOrder(any(UnifiedQueryRequest.class));
        verify(payTemplate, never()).cancelOrder(any(UnifiedCancelRequest.class));
        assertEquals(0, sleepCount.get());
    }

    @Test
    @DisplayName("条码收银等待输密并中途成功: 轮询查询到 SUCCESS 后即刻短路返回")
    void testExecute_pollingSuccess() {
        UnifiedPayRequest request = UnifiedPayRequest.builder()
                .channel(PayChannel.ALIPAY)
                .outTradeNo("BAR_POLL_001")
                .amount(new BigDecimal("99.00"))
                .authCode("281234567890123456")
                .subject("商超购物结算")
                .build();

        // 首次支付返回 USERPAYING
        PayResponse initialResp = PayResponse.builder()
                .channel(PayChannel.ALIPAY)
                .tradeType(PayTradeType.BARCODE)
                .outTradeNo("BAR_POLL_001")
                .tradeState(PayTradeState.USERPAYING)
                .success(true)
                .build();
        when(payTemplate.pay(any(UnifiedPayRequest.class))).thenReturn(initialResp);

        // 第一次查单仍处于 USERPAYING，第二次查单返回 SUCCESS
        PayResponse query1 = PayResponse.builder()
                .channel(PayChannel.ALIPAY)
                .outTradeNo("BAR_POLL_001")
                .tradeState(PayTradeState.USERPAYING)
                .success(true)
                .build();
        PayResponse query2 = PayResponse.builder()
                .channel(PayChannel.ALIPAY)
                .outTradeNo("BAR_POLL_001")
                .transactionId("ALI_TX_888")
                .amount(new BigDecimal("99.00"))
                .tradeState(PayTradeState.SUCCESS)
                .success(true)
                .build();

        when(payTemplate.queryOrder(any(UnifiedQueryRequest.class)))
                .thenReturn(query1)
                .thenReturn(query2);

        PayResponse response = executor.execute(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals(PayTradeState.SUCCESS, response.getTradeState());
        assertEquals("ALI_TX_888", response.getTransactionId());

        verify(payTemplate, times(1)).pay(any(UnifiedPayRequest.class));
        verify(payTemplate, times(2)).queryOrder(any(UnifiedQueryRequest.class));
        verify(payTemplate, never()).cancelOrder(any(UnifiedCancelRequest.class));
        assertTrue(sleepCount.get() >= 2);
    }

    @Test
    @DisplayName("条码收银超时未成功: 自动调用 cancelOrder 主动撤销冲正，消除资金账实不符")
    void testExecute_timeoutAutoReversal() {
        UnifiedPayRequest request = UnifiedPayRequest.builder()
                .channel(PayChannel.WECHAT_PAY)
                .merchantKey("store_shanghai")
                .outTradeNo("BAR_TIMEOUT_001")
                .amount(new BigDecimal("300.00"))
                .authCode("130000000000000001")
                .subject("大额餐饮消费")
                .build();

        // 首次支付返回 USERPAYING
        PayResponse initialResp = PayResponse.builder()
                .channel(PayChannel.WECHAT_PAY)
                .tradeType(PayTradeType.BARCODE)
                .outTradeNo("BAR_TIMEOUT_001")
                .tradeState(PayTradeState.USERPAYING)
                .success(true)
                .build();
        when(payTemplate.pay(any(UnifiedPayRequest.class))).thenReturn(initialResp);

        // 轮询一直处于 USERPAYING
        PayResponse queryingResp = PayResponse.builder()
                .channel(PayChannel.WECHAT_PAY)
                .outTradeNo("BAR_TIMEOUT_001")
                .tradeState(PayTradeState.USERPAYING)
                .success(true)
                .build();
        when(payTemplate.queryOrder(any(UnifiedQueryRequest.class))).thenReturn(queryingResp);

        // 撤销冲正返回 REVOKED
        PayResponse cancelResp = PayResponse.builder()
                .channel(PayChannel.WECHAT_PAY)
                .outTradeNo("BAR_TIMEOUT_001")
                .tradeState(PayTradeState.REVOKED)
                .success(true)
                .build();
        when(payTemplate.cancelOrder(any(UnifiedCancelRequest.class))).thenReturn(cancelResp);

        // 使用极短超时（10ms超时，5ms步长）进行确定性超时触发
        PayResponse response = executor.execute(request, Duration.ofMillis(10), Duration.ofMillis(5), true);

        assertNotNull(response);
        assertFalse(response.isSuccess(), "超时冲正后响应 success 必须为 false");
        assertEquals(PayTradeState.REVOKED, response.getTradeState());
        assertEquals("BAR_TIMEOUT_001", response.getOutTradeNo());
        assertTrue(response.getTradeStateDesc().contains("超时"));

        // 验证必须且只能调用了一次 cancelOrder
        ArgumentCaptor<UnifiedCancelRequest> cancelCaptor = ArgumentCaptor.forClass(UnifiedCancelRequest.class);
        verify(payTemplate, times(1)).cancelOrder(cancelCaptor.capture());
        UnifiedCancelRequest capturedCancel = cancelCaptor.getValue();
        assertEquals(PayChannel.WECHAT_PAY, capturedCancel.getChannel());
        assertEquals("store_shanghai", capturedCancel.getMerchantKey());
        assertEquals("BAR_TIMEOUT_001", capturedCancel.getOutTradeNo());
    }

    @Test
    @DisplayName("首次扣款发生网络通信异常: 自动降级为查单轮询，若最终成功则正常返回")
    void testExecute_networkExceptionFallbackToPolling() {
        UnifiedPayRequest request = UnifiedPayRequest.builder()
                .channel(PayChannel.ALIPAY)
                .outTradeNo("BAR_NET_001")
                .amount(new BigDecimal("20.00"))
                .authCode("281234567890000000")
                .subject("网络抖动重试测试")
                .build();

        // 首次扣款抛出网络异常
        when(payTemplate.pay(any(UnifiedPayRequest.class)))
                .thenThrow(new PayNetworkException("SocketTimeoutException: Read timed out"));

        // 轮询查单成功返回
        PayResponse querySuccess = PayResponse.builder()
                .channel(PayChannel.ALIPAY)
                .outTradeNo("BAR_NET_001")
                .transactionId("ALI_NET_OK")
                .tradeState(PayTradeState.SUCCESS)
                .success(true)
                .build();
        when(payTemplate.queryOrder(any(UnifiedQueryRequest.class))).thenReturn(querySuccess);

        PayResponse response = executor.execute(request);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals(PayTradeState.SUCCESS, response.getTradeState());
        assertEquals("ALI_NET_OK", response.getTransactionId());
        verify(payTemplate, never()).cancelOrder(any(UnifiedCancelRequest.class));
    }

    @Test
    @DisplayName("轮询收到不可逆终态 CLOSED: 立即终止轮询并触发撤销冲正")
    void testExecute_definitiveFailureShortCircuit() {
        UnifiedPayRequest request = UnifiedPayRequest.builder()
                .channel(PayChannel.WECHAT_PAY)
                .outTradeNo("BAR_CLOSED_001")
                .amount(new BigDecimal("15.00"))
                .authCode("130000000000000002")
                .subject("用户放弃支付")
                .build();

        // 首次返回 USERPAYING
        when(payTemplate.pay(any(UnifiedPayRequest.class))).thenReturn(PayResponse.builder()
                .channel(PayChannel.WECHAT_PAY)
                .tradeState(PayTradeState.USERPAYING)
                .success(true)
                .build());

        // 查单返回 CLOSED
        when(payTemplate.queryOrder(any(UnifiedQueryRequest.class))).thenReturn(PayResponse.builder()
                .channel(PayChannel.WECHAT_PAY)
                .tradeState(PayTradeState.CLOSED)
                .success(true)
                .build());

        when(payTemplate.cancelOrder(any(UnifiedCancelRequest.class))).thenReturn(PayResponse.builder()
                .channel(PayChannel.WECHAT_PAY)
                .tradeState(PayTradeState.REVOKED)
                .success(true)
                .build());

        PayResponse response = executor.execute(request, Duration.ofSeconds(10), Duration.ofSeconds(1), true);

        assertNotNull(response);
        assertEquals(PayTradeState.REVOKED, response.getTradeState());
        assertFalse(response.isSuccess());
        verify(payTemplate, times(1)).cancelOrder(any(UnifiedCancelRequest.class));
    }

    @Test
    @DisplayName("参数校验防卫: 缺失必要参数时 Fail-Fast 抛出 PAY_PARAM_INVALID")
    void testExecute_validationFailFast() {
        // 1. 空请求
        assertThrows(PayException.class, () -> executor.execute(null));

        // 2. 缺少 outTradeNo
        UnifiedPayRequest noOutTradeNo = UnifiedPayRequest.builder()
                .amount(new BigDecimal("10.00"))
                .authCode("130000000000000000")
                .build();
        PayException ex1 = assertThrows(PayException.class, () -> executor.execute(noOutTradeNo));
        assertEquals(PayResultCode.PAY_PARAM_INVALID, ex1.getResultCode());

        // 3. 缺少 authCode
        UnifiedPayRequest noAuthCode = UnifiedPayRequest.builder()
                .outTradeNo("T001")
                .amount(new BigDecimal("10.00"))
                .build();
        PayException ex2 = assertThrows(PayException.class, () -> executor.execute(noAuthCode));
        assertEquals(PayResultCode.PAY_PARAM_INVALID, ex2.getResultCode());

        // 4. 金额小于等于 0
        UnifiedPayRequest zeroAmount = UnifiedPayRequest.builder()
                .outTradeNo("T001")
                .authCode("130000000000000000")
                .amount(BigDecimal.ZERO)
                .build();
        PayException ex3 = assertThrows(PayException.class, () -> executor.execute(zeroAmount));
        assertEquals(PayResultCode.PAY_AMOUNT_INVALID, ex3.getResultCode());
    }
}
