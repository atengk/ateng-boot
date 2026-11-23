package io.github.atengk.boot.sample.excel.vo;

import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 费用报销明细行视图对象（用于模板填充）
 * <p>
 * 配合 ExcelUtil.fill 演示列表循环占位符 {.expenseType}、{.amount}、{.description}。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-08
 */
public class ReimbursementItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 费用科目
     */
    @ExcelProperty(value = "费用科目", index = 0)
    private String expenseType;

    /**
     * 报销金额
     */
    @ExcelProperty(value = "报销金额", index = 1)
    private BigDecimal amount;

    /**
     * 用途说明
     */
    @ExcelProperty(value = "用途说明", index = 2)
    private String description;

    public ReimbursementItemVO() {
    }

    public ReimbursementItemVO(String expenseType, BigDecimal amount, String description) {
        this.expenseType = expenseType;
        this.amount = amount;
        this.description = description;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getExpenseType() {
        return expenseType;
    }

    public void setExpenseType(String expenseType) {
        this.expenseType = expenseType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public static class Builder {
        private String expenseType;
        private BigDecimal amount;
        private String description;

        public Builder expenseType(String expenseType) {
            this.expenseType = expenseType;
            return this;
        }

        public Builder amount(BigDecimal amount) {
            this.amount = amount;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public ReimbursementItemVO build() {
            return new ReimbursementItemVO(expenseType, amount, description);
        }
    }
}
