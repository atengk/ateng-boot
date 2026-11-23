package io.github.atengk.boot.sample.excel.vo;

import io.github.atengk.boot.core.enums.SensitiveType;
import io.github.atengk.boot.excel.annotation.ExcelSensitive;
import io.github.atengk.boot.excel.annotation.ExcelTheme;
import io.github.atengk.boot.excel.enums.ExcelThemeEnum;
import io.github.atengk.boot.sample.excel.enums.EmployeeStatusEnum;
import io.github.atengk.boot.sample.excel.enums.SampleGenderEnum;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.apache.fesod.sheet.annotation.format.DateTimeFormat;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 员工花名册 Excel 视图对象
 * <p>
 * 涵盖敏感数据动态脱敏（@ExcelSensitive）、枚举双向转义（@ExcelEnum）、日期格式化与企业预设主题（@ExcelTheme）。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-08
 */
@ExcelTheme(theme = ExcelThemeEnum.BUSINESS_BLUE)
public class EmployeeRosterVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 员工工号
     */
    @ExcelProperty(value = "员工工号", index = 0)
    private Long id;

    /**
     * 员工姓名
     */
    @ExcelProperty(value = "姓名", index = 1)
    private String name;

    /**
     * 性别（枚举转义）
     */
    @ExcelProperty(value = "性别", index = 2)
    private SampleGenderEnum gender;

    /**
     * 联系电话（出界脱敏：保留前3后4）
     */
    @ExcelSensitive(SensitiveType.MOBILE_PHONE)
    @ExcelProperty(value = "手机号码", index = 3)
    private String phone;

    /**
     * 企业邮箱（出界脱敏）
     */
    @ExcelSensitive(SensitiveType.EMAIL)
    @ExcelProperty(value = "企业邮箱", index = 4)
    private String email;

    /**
     * 所属部门
     */
    @ExcelProperty(value = "所属部门", index = 5)
    private String department;

    /**
     * 在职状态（枚举转义）
     */
    @ExcelProperty(value = "在职状态", index = 6)
    private EmployeeStatusEnum status;

    /**
     * 基本月薪（数值）
     */
    @ExcelProperty(value = "基本月薪", index = 7)
    private BigDecimal salary;

    /**
     * 入职日期
     */
    @DateTimeFormat("yyyy-MM-dd")
    @ExcelProperty(value = "入职日期", index = 8)
    private LocalDate entryDate;

    public EmployeeRosterVO() {
    }

    public EmployeeRosterVO(Long id, String name, SampleGenderEnum gender, String phone, String email,
                            String department, EmployeeStatusEnum status, BigDecimal salary, LocalDate entryDate) {
        this.id = id;
        this.name = name;
        this.gender = gender;
        this.phone = phone;
        this.email = email;
        this.department = department;
        this.status = status;
        this.salary = salary;
        this.entryDate = entryDate;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long id;
        private String name;
        private SampleGenderEnum gender;
        private String phone;
        private String email;
        private String department;
        private EmployeeStatusEnum status;
        private BigDecimal salary;
        private LocalDate entryDate;

        public Builder id(Long id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder gender(SampleGenderEnum gender) {
            this.gender = gender;
            return this;
        }

        public Builder phone(String phone) {
            this.phone = phone;
            return this;
        }

        public Builder email(String email) {
            this.email = email;
            return this;
        }

        public Builder department(String department) {
            this.department = department;
            return this;
        }

        public Builder status(EmployeeStatusEnum status) {
            this.status = status;
            return this;
        }

        public Builder salary(BigDecimal salary) {
            this.salary = salary;
            return this;
        }

        public Builder entryDate(LocalDate entryDate) {
            this.entryDate = entryDate;
            return this;
        }

        public EmployeeRosterVO build() {
            return new EmployeeRosterVO(id, name, gender, phone, email, department, status, salary, entryDate);
        }
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

    public SampleGenderEnum getGender() {
        return gender;
    }

    public void setGender(SampleGenderEnum gender) {
        this.gender = gender;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public EmployeeStatusEnum getStatus() {
        return status;
    }

    public void setStatus(EmployeeStatusEnum status) {
        this.status = status;
    }

    public BigDecimal getSalary() {
        return salary;
    }

    public void setSalary(BigDecimal salary) {
        this.salary = salary;
    }

    public LocalDate getEntryDate() {
        return entryDate;
    }

    public void setEntryDate(LocalDate entryDate) {
        this.entryDate = entryDate;
    }
}
