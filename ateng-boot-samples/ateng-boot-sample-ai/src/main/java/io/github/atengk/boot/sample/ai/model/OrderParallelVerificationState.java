package io.github.atengk.boot.sample.ai.model;

import io.github.atengk.boot.ai.graph.TypedKey;
import io.github.atengk.boot.ai.graph.parallel.ParallelState;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 电商订单并发多维校验认知图状态模型
 *
 * @author Ateng
 * @since 2026-11-22
 */
public class OrderParallelVerificationState extends ParallelState {

    /**
     * 业务订单编号
     */
    public static final TypedKey<String> ORDER_ID = TypedKey.of("orderId", String.class);

    /**
     * 下单用户标识
     */
    public static final TypedKey<String> USER_ID = TypedKey.of("userId", String.class);

    /**
     * 订单交易金额
     */
    public static final TypedKey<BigDecimal> AMOUNT = TypedKey.of("amount", BigDecimal.class);

    /**
     * 商品 SKU 编码
     */
    public static final TypedKey<String> SKU_CODE = TypedKey.of("skuCode", String.class);

    /**
     * 模拟风控分支耗时毫秒数（用于演示单分支超时 BEST_EFFORT 降级）
     */
    public static final TypedKey<Long> RISK_DELAY_MS = TypedKey.of("riskDelayMs", Long.class);

    /**
     * 综合校验放行结果
     */
    public static final TypedKey<Boolean> APPROVED = TypedKey.of("approved", Boolean.class);

    public OrderParallelVerificationState() {
        this(new LinkedHashMap<>());
    }

    public OrderParallelVerificationState(Map<String, Object> initData) {
        super(initData);
    }

    public String getOrderId() {
        return get(ORDER_ID, "");
    }

    public void setOrderId(String orderId) {
        set(ORDER_ID, orderId);
    }

    public String getUserId() {
        return get(USER_ID, "");
    }

    public void setUserId(String userId) {
        set(USER_ID, userId);
    }

    public BigDecimal getAmount() {
        return get(AMOUNT, BigDecimal.ZERO);
    }

    public void setAmount(BigDecimal amount) {
        set(AMOUNT, amount);
    }

    public String getSkuCode() {
        return get(SKU_CODE, "");
    }

    public void setSkuCode(String skuCode) {
        set(SKU_CODE, skuCode);
    }

    public Long getRiskDelayMs() {
        return get(RISK_DELAY_MS, 0L);
    }

    public void setRiskDelayMs(Long riskDelayMs) {
        set(RISK_DELAY_MS, riskDelayMs);
    }
}
