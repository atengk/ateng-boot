package io.github.atengk.boot.sample.mail.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 多租户上下文动态路由发信请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantRouteMailRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 租户上下文标识（自动映射至同逻辑名称发信账号）
     */
    @NotBlank(message = "租户标识不能为空")
    private String tenantId;

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
}
