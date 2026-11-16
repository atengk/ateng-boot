package io.github.atengk.boot.sample.mail.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 出站防护（频控 / 日配额 / 幂等防重）演示请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboundGuardDemoRequest implements Serializable {

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
     * 业务幂等流水号（幂等防重演示使用）
     */
    private String bizId;

    /**
     * 连续突发发送次数（用于触发频控或配额上限，范围 1~20）
     */
    @Min(value = 1, message = "发送次数最小为 1")
    @Max(value = 20, message = "发送次数最大不超过 20")
    @Builder.Default
    private int burstCount = 2;
}
