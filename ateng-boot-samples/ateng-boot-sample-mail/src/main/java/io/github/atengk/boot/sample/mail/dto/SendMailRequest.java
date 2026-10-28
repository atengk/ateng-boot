package io.github.atengk.boot.sample.mail.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 场景邮件发送请求传输对象
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendMailRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 收件人电子邮箱
     */
    @NotBlank(message = "收件人电子邮箱不能为空")
    @Email(message = "收件人电子邮箱格式不合法")
    private String to;

    /**
     * 邮件主题
     */
    @NotBlank(message = "邮件主题不能为空")
    private String subject;

    /**
     * 邮件文本内容
     */
    @NotBlank(message = "邮件内容不能为空")
    private String content;

    /**
     * 路由账号名称 (为空时默认采用 default 通道)
     */
    private String account;

    /**
     * 业务操作用户 (用于模板渲染与上下文演示)
     */
    private String username;

    /**
     * 业务验证码 (用于模板邮件渲染)
     */
    private String verifyCode;

    /**
     * 动态覆盖发件人电子邮箱（可选，为空时采用账号默认发件人）
     */
    private String from;

    /**
     * 动态覆盖发件人中文昵称（可选，为空时采用账号默认昵称）
     */
    private String fromName;
}
