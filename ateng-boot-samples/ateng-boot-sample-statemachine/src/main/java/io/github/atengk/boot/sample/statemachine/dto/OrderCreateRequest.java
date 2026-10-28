package io.github.atengk.boot.sample.statemachine.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单创建请求传输对象
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class OrderCreateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 订单业务编号
     */
    @NotBlank(message = "订单编号不能为空")
    private String orderSn;

    /**
     * 订单金额
     */
    @NotNull(message = "订单金额不能为空")
    @DecimalMin(value = "0.01", message = "订单金额必须大于 0")
    private BigDecimal amount;

    public OrderCreateRequest() {
    }

    public OrderCreateRequest(String orderSn, BigDecimal amount) {
        this.orderSn = orderSn;
        this.amount = amount;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String orderSn;
        private BigDecimal amount;

        public Builder orderSn(String orderSn) {
            this.orderSn = orderSn;
            return this;
        }

        public Builder amount(BigDecimal amount) {
            this.amount = amount;
            return this;
        }

        public OrderCreateRequest build() {
            return new OrderCreateRequest(orderSn, amount);
        }
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
}
