package io.github.atengk.boot.sample.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 控制台管理员认证演练请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
public class AuthVerifyRequest {

    /**
     * 管理员用户名
     */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 管理员访问口令
     */
    @NotBlank(message = "密码不能为空")
    private String password;
}
