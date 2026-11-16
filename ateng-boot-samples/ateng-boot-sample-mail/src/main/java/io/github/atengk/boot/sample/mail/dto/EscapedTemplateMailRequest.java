package io.github.atengk.boot.sample.mail.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * HTML 安全转义与显式富文本透传模板发信请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EscapedTemplateMailRequest implements Serializable {

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
     * 用户昵称（将按 ${username} 自动执行 HTML 转义防 XSS）
     */
    @NotBlank(message = "用户昵称不能为空")
    private String username;

    /**
     * 受信任富文本徽章片段（将通过 $!{trustedBadgeHtml} 显式非转义透传）
     */
    private String trustedBadgeHtml;
}
