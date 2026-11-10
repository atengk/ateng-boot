package io.github.atengk.boot.sample.mail.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 外部介质动态账号供给发信请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DynamicAccountMailRequest implements Serializable {

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
     * 邮件正文
     */
    @NotBlank(message = "邮件正文不能为空")
    private String content;

    /**
     * 路由账号名称（外部数据库/配置中心存储的逻辑账号标识）
     */
    @NotBlank(message = "发信账号标识不能为空")
    private String account;

    /**
     * 动态覆盖发件人电子邮箱（可选，为空时采用账号默认发件人）
     */
    private String from;

    /**
     * 动态覆盖发件人中文昵称（可选，为空时采用账号默认昵称）
     */
    private String fromName;
}
