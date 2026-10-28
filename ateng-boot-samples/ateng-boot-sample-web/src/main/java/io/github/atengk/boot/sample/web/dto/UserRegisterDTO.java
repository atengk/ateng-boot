package io.github.atengk.boot.sample.web.dto;

import io.github.atengk.boot.core.validation.ValidationGroups;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 用户注册与更新传输载荷对象
 * 挂载 JSR-303 与分组校验注解，用于 Controller 传输第一道防线语法防卫
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Data
public class UserRegisterDTO {

    /**
     * 用户主键 ID（仅更新分组必填）
     */
    @NotNull(groups = ValidationGroups.Update.class, message = "更新操作用户ID不能为空")
    private Long id;

    /**
     * 用户姓名（必填）
     */
    @NotBlank(message = "用户姓名不能为空")
    private String name;

    /**
     * 年龄（1 ~ 150 岁之间）
     */
    @NotNull(message = "年龄不能为空")
    @Min(value = 1, message = "年龄必须大于0")
    @Max(value = 150, message = "年龄不能超过150岁")
    private Integer age;

    /**
     * 电子邮箱
     */
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    /**
     * 手机号码
     */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /**
     * 密码凭据
     */
    private String password;
}
