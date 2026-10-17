package io.github.atengk.boot.sample.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 示例用户登录请求传输对象
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserLoginDTO {

    /**
     * 登录账号 / 用户名
     */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 登录密码（敏感凭据，需在 @WebLog 中排除）
     */
    @NotBlank(message = "密码不能为空")
    private String password;
}
