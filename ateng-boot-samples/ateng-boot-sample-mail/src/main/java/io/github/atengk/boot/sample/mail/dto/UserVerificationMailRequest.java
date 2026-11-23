package io.github.atengk.boot.sample.mail.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用户注册/安全验证码邮件发送请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserVerificationMailRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 收件人电子邮箱
     */
    @NotBlank(message = "收件人电子邮箱不能为空")
    @Email(message = "收件人电子邮箱格式不合法")
    private String to;

    /**
     * 用户名/昵称
     */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 动态验证码
     */
    @NotBlank(message = "验证码不能为空")
    private String verifyCode;

    /**
     * 邮件自定义主题（可选）
     */
    private String subject;
}
