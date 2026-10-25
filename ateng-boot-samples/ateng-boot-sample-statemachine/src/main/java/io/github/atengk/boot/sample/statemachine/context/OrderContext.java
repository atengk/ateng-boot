package io.github.atengk.boot.sample.statemachine.context;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 电商订单状态机运行时业务上下文
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class OrderContext implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 订单主键 ID
     */
    private Long orderId;

    /**
     * 订单业务编号
     */
    private String orderSn;

    /**
     * 订单金额
     */
    private BigDecimal amount;

    /**
     * 风控评分（0~100 分，>= 80 判定为高风险异常订单）
     */
    private Integer riskScore;

    /**
     * 运单编号
     */
    private String trackingNo;

    /**
     * 物流跟踪轨迹备注
     */
    private String trackingRemark;

    /**
     * 触发本次跃迁的操作人
     */
    private String operator;

    /**
     * 异常触发标记（为 true 时在 Action 中主动抛出运行时异常，用于自验本地事务原子回滚）
     */
    private boolean failTrigger;

    /**
     * 自定义扩展属性上下文
     */
    private Map<String, Object> attributes = new HashMap<>();

    public OrderContext() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final OrderContext context = new OrderContext();

        public Builder orderId(Long orderId) {
            context.orderId = orderId;
            return this;
        }

        public Builder orderSn(String orderSn) {
            context.orderSn = orderSn;
            return this;
        }

        public Builder amount(BigDecimal amount) {
            context.amount = amount;
            return this;
        }

        public Builder riskScore(Integer riskScore) {
            context.riskScore = riskScore;
            return this;
        }

        public Builder trackingNo(String trackingNo) {
            context.trackingNo = trackingNo;
            return this;
        }

        public Builder trackingRemark(String trackingRemark) {
            context.trackingRemark = trackingRemark;
            return this;
        }

        public Builder operator(String operator) {
            context.operator = operator;
            return this;
        }

        public Builder failTrigger(boolean failTrigger) {
            context.failTrigger = failTrigger;
            return this;
        }

        public Builder attributes(Map<String, Object> attributes) {
            if (attributes != null) {
                context.attributes = attributes;
            }
            return this;
        }

        public OrderContext build() {
            return context;
        }
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getOrderSn() {
        return orderSn;
    }

    public void setOrderSn(String orderSn) {
        this.orderSn = orderSn;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Integer getRiskScore() {
        return riskScore;
    }

    public void setRiskScore(Integer riskScore) {
        this.riskScore = riskScore;
    }

    public String getTrackingNo() {
        return trackingNo;
    }

    public void setTrackingNo(String trackingNo) {
        this.trackingNo = trackingNo;
    }

    public String getTrackingRemark() {
        return trackingRemark;
    }

    public void setTrackingRemark(String trackingRemark) {
        this.trackingRemark = trackingRemark;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public boolean isFailTrigger() {
        return failTrigger;
    }

    public void setFailTrigger(boolean failTrigger) {
        this.failTrigger = failTrigger;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }
}
