package io.github.atengk.boot.sample.satoken.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 临时业务令牌签发请求传输对象
 * 适用于文件限时下载签名、扫码确认、邮件激活与敏感操作二次校验票据签发
 *
 * @author Ateng
 * @since 2026-11-13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TempTokenIssueDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 业务场景标识（如 FILE_DOWNLOAD、QR_CONFIRM、EMAIL_ACTIVATE）
     */
    @NotBlank(message = "业务场景标识不能为空")
    private String bizScene;

    /**
     * 关联目标业务资源标识（如文件 ID、订单编号）
     */
    @NotBlank(message = "目标资源标识不能为空")
    private String resourceId;

    /**
     * 业务资源名称描述
     */
    private String resourceName;

    /**
     * 临时令牌有效期（秒），未传默认 300 秒
     */
    @Positive(message = "有效期秒数必须大于 0")
    private Long timeoutSeconds;
}
