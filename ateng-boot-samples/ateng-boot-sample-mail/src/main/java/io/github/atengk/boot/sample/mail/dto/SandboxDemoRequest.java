package io.github.atengk.boot.sample.mail.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试沙箱双策略（白名单重定向 / 纯日志模拟）演示请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SandboxDemoRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 原始收件人电子邮箱
     */
    @NotBlank(message = "收件人电子邮箱不能为空")
    @Email(message = "收件人电子邮箱格式不合法")
    private String to;

    /**
     * 原始抄送人电子邮箱（用于验证非白名单重定向时强制清空 Cc）
     */
    @Email(message = "抄送人电子邮箱格式不合法")
    private String cc;

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
     * 非白名单重定向测试目标邮箱
     */
    @Email(message = "重定向目标电子邮箱格式不合法")
    private String redirectTo;

    /**
     * 允许真实外发的白名单匹配规则列表（支持通配符，如 *@internal.example.com）
     */
    @Builder.Default
    private List<String> allowedRecipients = new ArrayList<>();

    /**
     * 是否开启纯日志模拟模式（true 时不发起物理网络连接）
     */
    @Builder.Default
    private boolean logOnly = false;
}
