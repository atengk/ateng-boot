package io.github.atengk.boot.sample.mail.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 紧急线上故障/运维告警邮件请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemAlertMailRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 告警接收人电子邮箱
     */
    @NotBlank(message = "告警接收人电子邮箱不能为空")
    @Email(message = "接收人电子邮箱格式不合法")
    private String to;

    /**
     * 发生故障的服务标识（如：order-service）
     */
    @NotBlank(message = "服务标识不能为空")
    private String serviceName;

    /**
     * 告警级别（如：P0-CRITICAL, P1-WARNING）
     */
    @NotBlank(message = "告警级别不能为空")
    private String alertLevel;

    /**
     * 错误信息与简要堆栈
     */
    @NotBlank(message = "错误信息不能为空")
    private String errorMessage;

    /**
     * 是否请求阅读回执（默认 true）
     */
    @Builder.Default
    private boolean requestReadReceipt = true;

    /**
     * 合规审计归档密送邮箱（可选）
     */
    private String archiveBcc;
}
