package io.github.atengk.boot.sample.ai.model;

import io.github.atengk.boot.ai.graph.TypedKey;
import io.github.atengk.boot.ai.graph.pipeline.PipelineState;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 电商订单履约流水线强类型状态契约模型
 * <p>
 * 继承自 {@link PipelineState}，封装订单编号、订单金额、商品品类、信用评级、
 * VIP 跳过标识、核验状态、风控等级、支付状态与发货运单号等全链路业务属性。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
public class OrderFulfillmentState extends PipelineState {

    /**
     * 业务订单编号
     */
    public static final TypedKey<String> ORDER_ID = TypedKey.of("orderId", String.class);

    /**
     * 订单交易金额
     */
    public static final TypedKey<BigDecimal> AMOUNT = TypedKey.of("amount", BigDecimal.class);

    /**
     * 商品类型 (PHYSICAL: 实物发货 / VIRTUAL: 虚拟卡密)
     */
    public static final TypedKey<String> ITEM_TYPE = TypedKey.of("itemType", String.class);

    /**
     * 买家信用评分 (300-900)
     */
    public static final TypedKey<Integer> CREDIT_SCORE = TypedKey.of("creditScore", Integer.class);

    /**
     * 是否启用 VIP 特权跳过风控审核
     */
    public static final TypedKey<Boolean> VIP_BYPASS = TypedKey.of("vipBypass", Boolean.class);

    /**
     * 订单真实性与库存校验状态
     */
    public static final TypedKey<Boolean> VERIFIED = TypedKey.of("verified", Boolean.class);

    /**
     * 风控评估等级 (LOW / MEDIUM / HIGH)
     */
    public static final TypedKey<String> RISK_LEVEL = TypedKey.of("riskLevel", String.class);

    /**
     * 资金清结算支付状态 (SUCCESS / FAILED)
     */
    public static final TypedKey<String> PAYMENT_STATUS = TypedKey.of("paymentStatus", String.class);

    /**
     * 发货运单号或数字卡密序列号
     */
    public static final TypedKey<String> TRACKING_NUMBER = TypedKey.of("trackingNumber", String.class);

    /**
     * 人工复核审批决断意见
     */
    public static final TypedKey<String> AUDIT_DECISION = TypedKey.of("auditDecision", String.class);

    /**
     * 订单备注说明
     */
    public static final TypedKey<String> REMARKS = TypedKey.of("remarks", String.class);

    /**
     * 无参缺省构造器
     */
    public OrderFulfillmentState() {
        this(new LinkedHashMap<>());
    }

    /**
     * 基于初始字典构造状态模型
     *
     * @param initData 初始状态字典
     */
    public OrderFulfillmentState(Map<String, Object> initData) {
        super(initData);
    }

    public String getOrderId() {
        return get(ORDER_ID, "");
    }

    public void setOrderId(String orderId) {
        set(ORDER_ID, orderId);
    }

    public BigDecimal getAmount() {
        return get(AMOUNT, BigDecimal.ZERO);
    }

    public void setAmount(BigDecimal amount) {
        set(AMOUNT, amount);
    }

    public String getItemType() {
        return get(ITEM_TYPE, "PHYSICAL");
    }

    public void setItemType(String itemType) {
        set(ITEM_TYPE, itemType);
    }

    public Integer getCreditScore() {
        return get(CREDIT_SCORE, 700);
    }

    public void setCreditScore(Integer creditScore) {
        set(CREDIT_SCORE, creditScore);
    }

    public boolean isVipBypass() {
        return Boolean.TRUE.equals(get(VIP_BYPASS, false));
    }

    public void setVipBypass(boolean vipBypass) {
        set(VIP_BYPASS, vipBypass);
    }

    public boolean isVerified() {
        return Boolean.TRUE.equals(get(VERIFIED, false));
    }

    public void setVerified(boolean verified) {
        set(VERIFIED, verified);
    }

    public String getRiskLevel() {
        return get(RISK_LEVEL, "LOW");
    }

    public void setRiskLevel(String riskLevel) {
        set(RISK_LEVEL, riskLevel);
    }

    public String getPaymentStatus() {
        return get(PAYMENT_STATUS, "");
    }

    public void setPaymentStatus(String paymentStatus) {
        set(PAYMENT_STATUS, paymentStatus);
    }

    public String getTrackingNumber() {
        return get(TRACKING_NUMBER, "");
    }

    public void setTrackingNumber(String trackingNumber) {
        set(TRACKING_NUMBER, trackingNumber);
    }

    public String getAuditDecision() {
        return get(AUDIT_DECISION, "");
    }

    public void setAuditDecision(String auditDecision) {
        set(AUDIT_DECISION, auditDecision);
    }

    public String getRemarks() {
        return get(REMARKS, "");
    }

    public void setRemarks(String remarks) {
        set(REMARKS, remarks);
    }
}
