package io.github.atengk.boot.sample.statemachine.vo;

import io.github.atengk.boot.sample.statemachine.constant.OrderState;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单展示视图对象
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class OrderVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 订单 ID
     */
    private Long id;

    /**
     * 订单编号
     */
    private String orderSn;

    /**
     * 当前状态
     */
    private OrderState status;

    /**
     * 状态中文说明
     */
    private String statusDesc;

    /**
     * 订单金额
     */
    private BigDecimal amount;

    /**
     * 运单编号
     */
    private String trackingNo;

    /**
     * 物流轨迹说明
     */
    private String trackingRemark;

    /**
     * 风控评分
     */
    private Integer riskScore;

    /**
     * 乐观锁版本号
     */
    private Integer version;

    /**
     * 最近更新时间
     */
    private LocalDateTime updatedTime;

    public OrderVO() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final OrderVO vo = new OrderVO();

        public Builder id(Long id) {
            vo.id = id;
            return this;
        }

        public Builder orderSn(String orderSn) {
            vo.orderSn = orderSn;
            return this;
        }

        public Builder status(OrderState status) {
            vo.status = status;
            return this;
        }

        public Builder statusDesc(String statusDesc) {
            vo.statusDesc = statusDesc;
            return this;
        }

        public Builder amount(BigDecimal amount) {
            vo.amount = amount;
            return this;
        }

        public Builder trackingNo(String trackingNo) {
            vo.trackingNo = trackingNo;
            return this;
        }

        public Builder trackingRemark(String trackingRemark) {
            vo.trackingRemark = trackingRemark;
            return this;
        }

        public Builder riskScore(Integer riskScore) {
            vo.riskScore = riskScore;
            return this;
        }

        public Builder version(Integer version) {
            vo.version = version;
            return this;
        }

        public Builder updatedTime(LocalDateTime updatedTime) {
            vo.updatedTime = updatedTime;
            return this;
        }

        public OrderVO build() {
            return vo;
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderSn() {
        return orderSn;
    }

    public void setOrderSn(String orderSn) {
        this.orderSn = orderSn;
    }

    public OrderState getStatus() {
        return status;
    }

    public void setStatus(OrderState status) {
        this.status = status;
    }

    public String getStatusDesc() {
        return statusDesc;
    }

    public void setStatusDesc(String statusDesc) {
        this.statusDesc = statusDesc;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
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

    public Integer getRiskScore() {
        return riskScore;
    }

    public void setRiskScore(Integer riskScore) {
        this.riskScore = riskScore;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public LocalDateTime getUpdatedTime() {
        return updatedTime;
    }

    public void setUpdatedTime(LocalDateTime updatedTime) {
        this.updatedTime = updatedTime;
    }
}
