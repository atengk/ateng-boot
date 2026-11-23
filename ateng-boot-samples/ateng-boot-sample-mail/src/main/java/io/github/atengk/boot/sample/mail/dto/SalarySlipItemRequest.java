package io.github.atengk.boot.sample.mail.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 批量工资条/月度账单合并单项数据传输对象
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalarySlipItemRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 员工电子邮箱
     */
    @NotBlank(message = "员工电子邮箱不能为空")
    @Email(message = "员工电子邮箱格式不合法")
    private String email;

    /**
     * 员工姓名
     */
    @NotBlank(message = "员工姓名不能为空")
    private String employeeName;

    /**
     * 计薪周期（如：2026年10月）
     */
    @NotBlank(message = "计薪周期不能为空")
    private String salaryPeriod;

    /**
     * 实发工资金额
     */
    @NotNull(message = "实发工资不能为空")
    private BigDecimal netSalary;
}
