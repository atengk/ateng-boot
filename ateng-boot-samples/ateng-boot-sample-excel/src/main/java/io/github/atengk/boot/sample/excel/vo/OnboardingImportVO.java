package io.github.atengk.boot.sample.excel.vo;

import io.github.atengk.boot.excel.annotation.ExcelDict;
import io.github.atengk.boot.excel.annotation.ExcelDropdown;
import io.github.atengk.boot.excel.annotation.ExcelPrompt;
import io.github.atengk.boot.sample.excel.enums.SampleGenderEnum;
import io.github.atengk.boot.sample.excel.provider.SampleDepartmentDropdownProvider;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.apache.fesod.sheet.annotation.format.DateTimeFormat;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 员工入职导入与校验视图对象
 * <p>
 * 涵盖智能防呆模板生成（静态/动态下拉 @ExcelDropdown、批注说明 @ExcelPrompt）、
 * JSR-303 分层校验（非空、正则、邮箱、正数）与枚举/字典双向转义。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-08
 */
public class OnboardingImportVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 员工姓名
     */
    @NotBlank(message = "员工姓名不能为空")
    @ExcelPrompt("请输入 2-20 位员工真实姓名，不可包含特殊符号")
    @ExcelProperty(value = "员工姓名", index = 0)
    private String name;

    /**
     * 手机号码
     */
    @NotBlank(message = "手机号码不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号码格式不合法")
    @ExcelPrompt("请输入中国大陆 11 位合法手机号")
    @ExcelProperty(value = "手机号码", index = 1)
    private String phone;

    /**
     * 电子邮箱
     */
    @NotBlank(message = "电子邮箱不能为空")
    @Email(message = "电子邮箱格式不正确")
    @ExcelProperty(value = "电子邮箱", index = 2)
    private String email;

    /**
     * 性别（静态数组下拉）
     */
    @NotNull(message = "性别不能为空")
    @ExcelDropdown({"男", "女", "未知"})
    @ExcelProperty(value = "性别", index = 3)
    private SampleGenderEnum gender;

    /**
     * 所属部门（动态 SPI 提供者下拉）
     */
    @NotBlank(message = "所属部门不能为空")
    @ExcelDropdown(source = SampleDepartmentDropdownProvider.class)
    @ExcelProperty(value = "所属部门", index = 4)
    private String department;

    /**
     * 在职状态（业务字典转义）
     */
    @NotBlank(message = "在职状态不能为空")
    @ExcelDict("employee_status")
    @ExcelProperty(value = "在职状态", index = 5)
    private String status;

    /**
     * 期望月薪
     */
    @NotNull(message = "期望月薪不能为空")
    @Positive(message = "期望月薪必须大于 0")
    @ExcelProperty(value = "期望月薪", index = 6)
    private BigDecimal expectedSalary;

    /**
     * 预计入职日期
     */
    @NotNull(message = "预计入职日期不能为空")
    @DateTimeFormat("yyyy-MM-dd")
    @ExcelProperty(value = "预计入职日期", index = 7)
    private LocalDate entryDate;

    public OnboardingImportVO() {
    }

    public OnboardingImportVO(String name, String phone, String email, SampleGenderEnum gender,
                              String department, String status, BigDecimal expectedSalary, LocalDate entryDate) {
        this.name = name;
        this.phone = phone;
        this.email = email;
        this.gender = gender;
        this.department = department;
        this.status = status;
        this.expectedSalary = expectedSalary;
        this.entryDate = entryDate;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String phone;
        private String email;
        private SampleGenderEnum gender;
        private String department;
        private String status;
        private BigDecimal expectedSalary;
        private LocalDate entryDate;

        public Builder name(String name) {
            this.name = name;
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

        public Builder gender(SampleGenderEnum gender) {
            this.gender = gender;
            return this;
        }

        public Builder department(String department) {
            this.department = department;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder expectedSalary(BigDecimal expectedSalary) {
            this.expectedSalary = expectedSalary;
            return this;
        }

        public Builder entryDate(LocalDate entryDate) {
            this.entryDate = entryDate;
            return this;
        }

        public OnboardingImportVO build() {
            return new OnboardingImportVO(name, phone, email, gender, department, status, expectedSalary, entryDate);
        }
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

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public SampleGenderEnum getGender() {
        return gender;
    }

    public void setGender(SampleGenderEnum gender) {
        this.gender = gender;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public BigDecimal getExpectedSalary() {
        return expectedSalary;
    }

    public void setExpectedSalary(BigDecimal expectedSalary) {
        this.expectedSalary = expectedSalary;
    }

    public LocalDate getEntryDate() {
        return entryDate;
    }

    public void setEntryDate(LocalDate entryDate) {
        this.entryDate = entryDate;
    }
}
