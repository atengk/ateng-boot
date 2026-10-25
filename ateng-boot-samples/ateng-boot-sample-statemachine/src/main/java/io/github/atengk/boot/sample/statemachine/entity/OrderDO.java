package io.github.atengk.boot.sample.statemachine.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import io.github.atengk.boot.sample.statemachine.constant.OrderState;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单持久化数据库实体
 *
 * @author Ateng
 * @since 2026-10-25
 */
@TableName("t_order")
public class OrderDO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 订单自增主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 订单业务编号
     */
    @TableField("order_sn")
    private String orderSn;

    /**
     * 当前订单生命周期状态
     */
    @TableField("status")
    private OrderState status;

    /**
     * 订单实付金额
     */
    @TableField("amount")
    private BigDecimal amount;

    /**
     * 物流运单号
     */
    @TableField("tracking_no")
    private String trackingNo;

    /**
     * 最新物流跟踪轨迹
     */
    @TableField("tracking_remark")
    private String trackingRemark;

    /**
     * 风控评分
     */
    @TableField("risk_score")
    private Integer riskScore;

    /**
     * 乐观锁版本号（支持 CAS 并发防重）
     */
    @Version
    @TableField("version")
    private Integer version;

    /**
     * 创建时间
     */
    @TableField("created_time")
    private LocalDateTime createdTime;

    /**
     * 最近更新时间
     */
    @TableField("updated_time")
    private LocalDateTime updatedTime;

    public OrderDO() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final OrderDO order = new OrderDO();

        public Builder id(Long id) {
            order.id = id;
            return this;
        }

        public Builder orderSn(String orderSn) {
            order.orderSn = orderSn;
            return this;
        }

        public Builder status(OrderState status) {
            order.status = status;
            return this;
        }

        public Builder amount(BigDecimal amount) {
            order.amount = amount;
            return this;
        }

        public Builder trackingNo(String trackingNo) {
            order.trackingNo = trackingNo;
            return this;
        }

        public Builder trackingRemark(String trackingRemark) {
            order.trackingRemark = trackingRemark;
            return this;
        }

        public Builder riskScore(Integer riskScore) {
            order.riskScore = riskScore;
            return this;
        }

        public Builder version(Integer version) {
            order.version = version;
            return this;
        }

        public Builder createdTime(LocalDateTime createdTime) {
            order.createdTime = createdTime;
            return this;
        }

        public Builder updatedTime(LocalDateTime updatedTime) {
            order.updatedTime = updatedTime;
            return this;
        }

        public OrderDO build() {
            return order;
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

    public LocalDateTime getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(LocalDateTime createdTime) {
        this.createdTime = createdTime;
    }

    public LocalDateTime getUpdatedTime() {
        return updatedTime;
    }

    public void setUpdatedTime(LocalDateTime updatedTime) {
        this.updatedTime = updatedTime;
    }
}
