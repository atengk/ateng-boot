package io.github.atengk.boot.sample.mail.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 主备账号顺延容灾（backupAccounts）演示请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailoverDemoRequest implements Serializable {

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
     * 邮件正文内容
     */
    @NotBlank(message = "邮件正文不能为空")
    private String content;

    /**
     * 主通道原发件人邮箱（切换到备用账号后将自动写入 Reply-To 保留回复链路）
     */
    @Email(message = "主通道发件人电子邮箱格式不合法")
    private String primaryFrom;
}
