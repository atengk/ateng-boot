package io.github.atengk.boot.sample.statemachine.dto;

import io.github.atengk.boot.sample.statemachine.constant.OrderEvent;
import jakarta.validation.constraints.NotNull;

import java.io.Serial;
import java.io.Serializable;

/**
 * 订单状态机流转触发请求对象
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class OrderTransitionRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 目标驱动事件
     */
    @NotNull(message = "驱动事件不能为空")
    private OrderEvent event;

    /**
     * 风控评分（用于 Choice 分支流转）
     */
    private Integer riskScore;

    /**
     * 运单编号（用于发货事件）
     */
    private String trackingNo;

    /**
     * 物流动态说明
     */
    private String trackingRemark;

    /**
     * 操作人标识
     */
    private String operator;

    /**
     * 异常触发标记（模拟 Action 失败事务回滚）
     */
    private Boolean failTrigger;

    public OrderTransitionRequest() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final OrderTransitionRequest request = new OrderTransitionRequest();

        public Builder event(OrderEvent event) {
            request.event = event;
            return this;
        }

        public Builder riskScore(Integer riskScore) {
            request.riskScore = riskScore;
            return this;
        }

        public Builder trackingNo(String trackingNo) {
            request.trackingNo = trackingNo;
            return this;
        }

        public Builder trackingRemark(String trackingRemark) {
            request.trackingRemark = trackingRemark;
            return this;
        }

        public Builder operator(String operator) {
            request.operator = operator;
            return this;
        }

        public Builder failTrigger(Boolean failTrigger) {
            request.failTrigger = failTrigger;
            return this;
        }

        public OrderTransitionRequest build() {
            return request;
        }
    }

    public OrderEvent getEvent() {
        return event;
    }

    public void setEvent(OrderEvent event) {
        this.event = event;
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

    public Boolean getFailTrigger() {
        return failTrigger;
    }

    public void setFailTrigger(Boolean failTrigger) {
        this.failTrigger = failTrigger;
    }
}
