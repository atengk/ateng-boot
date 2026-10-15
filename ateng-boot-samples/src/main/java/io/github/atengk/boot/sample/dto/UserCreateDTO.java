package io.github.atengk.boot.sample.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 示例创建用户请求传输对象
 * 承载用户基础注册入参，并通过标准 Bean Validation 实施契约前置约束
 *
 * @author Ateng
 * @since 2026-10-15
 */
@Getter
@Setter
public class UserCreateDTO {

    /**
     * 用户姓名
     */
    @NotBlank(message = "用户姓名不能为空")
    private String name;

    /**
     * 用户年龄
     */
    @NotNull(message = "用户年龄不能为空")
    @Min(value = 1, message = "用户年龄必须大于 0")
    private Integer age;

    /**
     * 电子邮箱
     */
    @Email(message = "电子邮箱格式不正确")
    private String email;
}
