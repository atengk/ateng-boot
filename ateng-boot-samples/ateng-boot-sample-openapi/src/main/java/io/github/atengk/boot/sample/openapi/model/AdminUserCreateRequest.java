package io.github.atengk.boot.sample.openapi.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理端用户创建请求对象
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Schema(description = "管理端用户创建请求参数")
public class AdminUserCreateRequest {

    @NotBlank(message = "用户账号不能为空")
    @Size(min = 4, max = 32, message = "账号长度必须在 4 到 32 位之间")
    @Schema(description = "用户账号", example = "admin_dev", requiredMode = Schema.RequiredMode.REQUIRED)
    private String username;

    @NotBlank(message = "用户姓名不能为空")
    @Schema(description = "用户真实姓名", example = "张三", requiredMode = Schema.RequiredMode.REQUIRED)
    private String realName;

    @Email(message = "电子邮箱格式不正确")
    @Schema(description = "工作电子邮箱", example = "zhangsan@example.com")
    private String email;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}
