package io.github.atengk.boot.excel.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.io.Serializable;

/**
 * 导入数据校验测试实体
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportUserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ExcelProperty("用户姓名")
    @NotBlank(message = "用户姓名不可为空")
    private String name;

    @ExcelProperty("手机号码")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号码格式不合法")
    private String phone;

    @ExcelProperty("用户年龄")
    @Min(value = 18, message = "年龄不能小于18周岁")
    private Integer age;

    @ExcelProperty("电子邮箱")
    @Email(message = "邮箱格式不正确")
    private String email;
}
