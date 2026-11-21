package io.github.atengk.boot.sample.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 动态维护静默规则创建请求 DTO
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Data
public class SampleSilenceCreateRequest {

    /**
     * 服务名称匹配模式（如 order-* 或 *）
     */
    @NotBlank(message = "静默匹配模式不能为空")
    @Size(max = 128, message = "静默匹配模式长度不能超过 128 个字符")
    private String pattern;

    /**
     * 静默有效时长（秒），为空时采用服务端默认 TTL
     */
    @Min(value = 1, message = "静默时长至少为 1 秒")
    @Max(value = 86400, message = "单次演练静默时长不得超过 86400 秒")
    private Long ttlSeconds;

    /**
     * 维护静默变更原因说明
     */
    @Size(max = 256, message = "静默原因长度不能超过 256 个字符")
    private String reason;
}
