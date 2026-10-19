package io.github.atengk.boot.sample.satoken.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 防暴力破解登录请求传输契约对象
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginDefenseRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户登录账号
     */
    @NotBlank(message = "登录账号不能为空")
    private String username;

    /**
     * 用户登录密码
     */
    @NotBlank(message = "登录密码不能为空")
    private String password;
}
