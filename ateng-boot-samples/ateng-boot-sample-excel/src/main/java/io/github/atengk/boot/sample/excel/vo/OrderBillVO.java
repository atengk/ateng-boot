package io.github.atengk.boot.sample.excel.vo;

import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单账单流水 Excel 视图对象
 * <p>
 * 配合 OrderStreamingController 演示海量数据游标导出、PageResult 流式导出与跨 Sheet 自动轮转分割。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-08
 */
public class OrderBillVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 账单流水号（游标自增主键）
     */
    @ExcelProperty(value = "流水号", index = 0)
    private Long id;

    /**
     * 业务订单号
     */
    @ExcelProperty(value = "订单编号", index = 1)
    private String orderNo;

    /**
     * 客户名称
     */
    @ExcelProperty(value = "客户名称", index = 2)
    private String customerName;

    /**
     * 交易金额
     */
    @ExcelProperty(value = "交易金额", index = 3)
    private BigDecimal amount;

    /**
     * 支付渠道
     */
    @ExcelProperty(value = "支付方式", index = 4)
    private String paymentMethod;

    /**
     * 订单状态
     */
    @ExcelProperty(value = "订单状态", index = 5)
    private String status;

    /**
     * 交易时间
     */
    @ExcelProperty(value = "交易时间", index = 6)
    private String createTime;

    public OrderBillVO() {
    }

    public OrderBillVO(Long id, String orderNo, String customerName, BigDecimal amount,
                       String paymentMethod, String status, String createTime) {
        this.id = id;
        this.orderNo = orderNo;
        this.customerName = customerName;
        this.amount = amount;
        this.paymentMethod = paymentMethod;
        this.status = status;
        this.createTime = createTime;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public static class Builder {
        private Long id;
        private String orderNo;
        private String customerName;
        private BigDecimal amount;
        private String paymentMethod;
        private String status;
        private String createTime;

        public Builder id(Long id) {
            this.id = id;
            return this;
        }

        public Builder orderNo(String orderNo) {
            this.orderNo = orderNo;
            return this;
        }

        public Builder customerName(String customerName) {
            this.customerName = customerName;
            return this;
        }

        public Builder amount(BigDecimal amount) {
            this.amount = amount;
            return this;
        }

        public Builder paymentMethod(String paymentMethod) {
            this.paymentMethod = paymentMethod;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder createTime(String createTime) {
            this.createTime = createTime;
            return this;
        }

        public OrderBillVO build() {
            return new OrderBillVO(id, orderNo, customerName, amount, paymentMethod, status, createTime);
        }
    }
}
