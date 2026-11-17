package io.github.atengk.boot.sample.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 图形验证码登录演示请求载荷
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
public class CaptchaLoginDTO {

    /**
     * 登录账号
     */
    @NotBlank(message = "登录账号不能为空")
    private String username;

    /**
     * 登录密码
     */
    @NotBlank(message = "登录密码不能为空")
    private String password;

    /**
     * 图形验证码唯一键
     */
    @NotBlank(message = "图形验证码键不能为空")
    private String captchaKey;

    /**
     * 用户输入的图形验证码答案
     */
    @NotBlank(message = "图形验证码不能为空")
    private String captchaCode;
}
