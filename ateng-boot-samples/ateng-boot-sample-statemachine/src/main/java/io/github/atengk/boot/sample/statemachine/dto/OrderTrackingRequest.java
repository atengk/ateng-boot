package io.github.atengk.boot.sample.statemachine.dto;

import jakarta.validation.constraints.NotBlank;

import java.io.Serial;
import java.io.Serializable;

/**
 * 追加物流追踪信息请求对象（演示内部自跃迁）
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class OrderTrackingRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 运单编号
     */
    @NotBlank(message = "运单编号不能为空")
    private String trackingNo;

    /**
     * 物流动态说明
     */
    @NotBlank(message = "物流动态说明不能为空")
    private String trackingRemark;

    public OrderTrackingRequest() {
    }

    public OrderTrackingRequest(String trackingNo, String trackingRemark) {
        this.trackingNo = trackingNo;
        this.trackingRemark = trackingRemark;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String trackingNo;
        private String trackingRemark;

        public Builder trackingNo(String trackingNo) {
            this.trackingNo = trackingNo;
            return this;
        }

        public Builder trackingRemark(String trackingRemark) {
            this.trackingRemark = trackingRemark;
            return this;
        }

        public OrderTrackingRequest build() {
            return new OrderTrackingRequest(trackingNo, trackingRemark);
        }
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
}
