package io.github.atengk.boot.sample.mybatisplus.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 创建系统用户请求载荷对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
public class UserCreateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户姓名
     */
    @NotBlank(message = "用户姓名不能为空")
    private String name;

    /**
     * 用户年龄
     */
    @NotNull(message = "年龄不能为空")
    @Min(value = 1, message = "年龄必须大于 0")
    private Integer age;

    /**
     * 电子邮箱（入库将自动通过 EncryptTypeHandler 透明加密）
     */
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    /**
     * 手机号码（入库将自动通过 EncryptTypeHandler 透明加密）
     */
    @NotBlank(message = "手机号不能为空")
    private String phone;

    /**
     * 所属部门 ID
     */
    private Long deptId = 100L;

    /**
     * 模拟当前上下文操作人标识（可选，用于演示 CurrentUserProvider 自动填充）
     */
    private String operator;

    /**
     * 显式指定的创建人（可选，用于演示 DefaultMetaObjectHandler 防御性不覆盖已有值）
     */
    private String explicitCreateBy;
}
