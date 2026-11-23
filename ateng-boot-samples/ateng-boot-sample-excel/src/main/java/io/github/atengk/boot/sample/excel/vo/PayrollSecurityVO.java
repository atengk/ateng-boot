package io.github.atengk.boot.sample.excel.vo;

import io.github.atengk.boot.core.enums.SensitiveType;
import io.github.atengk.boot.excel.annotation.ExcelColumnLock;
import io.github.atengk.boot.excel.annotation.ExcelFormulaAllowed;
import io.github.atengk.boot.excel.annotation.ExcelSensitive;
import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 薪酬安全与防护对照视图对象
 * <p>
 * 涵盖敏感数据动态脱敏（@ExcelSensitive）、公式注入主动防护（CWE-1236 对照）、
 * 公式白名单豁免（@ExcelFormulaAllowed）以及列级防呆只读保护锁定（@ExcelColumnLock）。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-08
 */
public class PayrollSecurityVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 员工工号（系统只读锁定）
     */
    @ExcelColumnLock(locked = true)
    @ExcelProperty(value = "员工工号", index = 0)
    private Long id;

    /**
     * 员工姓名（中文姓名动态脱敏）
     */
    @ExcelSensitive(SensitiveType.CHINESE_NAME)
    @ExcelProperty(value = "员工姓名", index = 1)
    private String name;

    /**
     * 联系手机（手机号保留前3后4脱敏）
     */
    @ExcelSensitive(SensitiveType.MOBILE_PHONE)
    @ExcelProperty(value = "联系手机", index = 2)
    private String phone;

    /**
     * 身份证号（身份证掩码脱敏）
     */
    @ExcelSensitive(SensitiveType.ID_CARD)
    @ExcelProperty(value = "身份证号", index = 3)
    private String idCard;

    /**
     * 基础月薪
     */
    @ExcelProperty(value = "基础月薪", index = 4)
    private BigDecimal baseSalary;

    /**
     * 绩效核算公式（白名单豁免：允许作为真实公式导出）
     */
    @ExcelFormulaAllowed
    @ExcelProperty(value = "绩效核算公式", index = 5)
    private String performanceFormula;

    /**
     * 外部备注（包含高危注入字符如 =,+,-,@ 等，写出时自动前置单引号防御）
     */
    @ExcelProperty(value = "外部备注", index = 6)
    private String externalRemark;

    /**
     * 核定职级（只读锁定）
     */
    @ExcelColumnLock(locked = true)
    @ExcelProperty(value = "核定职级", index = 7)
    private String lockedGrade;

    public PayrollSecurityVO() {
    }

    public PayrollSecurityVO(Long id, String name, String phone, String idCard, BigDecimal baseSalary,
                             String performanceFormula, String externalRemark, String lockedGrade) {
        this.id = id;
        this.name = name;
        this.phone = phone;
        this.idCard = idCard;
        this.baseSalary = baseSalary;
        this.performanceFormula = performanceFormula;
        this.externalRemark = externalRemark;
        this.lockedGrade = lockedGrade;
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getIdCard() {
        return idCard;
    }

    public void setIdCard(String idCard) {
        this.idCard = idCard;
    }

    public BigDecimal getBaseSalary() {
        return baseSalary;
    }

    public void setBaseSalary(BigDecimal baseSalary) {
        this.baseSalary = baseSalary;
    }

    public String getPerformanceFormula() {
        return performanceFormula;
    }

    public void setPerformanceFormula(String performanceFormula) {
        this.performanceFormula = performanceFormula;
    }

    public String getExternalRemark() {
        return externalRemark;
    }

    public void setExternalRemark(String externalRemark) {
        this.externalRemark = externalRemark;
    }

    public String getLockedGrade() {
        return lockedGrade;
    }

    public void setLockedGrade(String lockedGrade) {
        this.lockedGrade = lockedGrade;
    }

    public static class Builder {
        private Long id;
        private String name;
        private String phone;
        private String idCard;
        private BigDecimal baseSalary;
        private String performanceFormula;
        private String externalRemark;
        private String lockedGrade;

        public Builder id(Long id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder phone(String phone) {
            this.phone = phone;
            return this;
        }

        public Builder idCard(String idCard) {
            this.idCard = idCard;
            return this;
        }

        public Builder baseSalary(BigDecimal baseSalary) {
            this.baseSalary = baseSalary;
            return this;
        }

        public Builder performanceFormula(String performanceFormula) {
            this.performanceFormula = performanceFormula;
            return this;
        }

        public Builder externalRemark(String externalRemark) {
            this.externalRemark = externalRemark;
            return this;
        }

        public Builder lockedGrade(String lockedGrade) {
            this.lockedGrade = lockedGrade;
            return this;
        }

        public PayrollSecurityVO build() {
            return new PayrollSecurityVO(id, name, phone, idCard, baseSalary, performanceFormula, externalRemark, lockedGrade);
        }
    }
}
