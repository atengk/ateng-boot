package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 微观突发滑动限流与配额尝试请求传输对象
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRateLimitRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 调用方 API Key 标识 (可选)
     */
    @Builder.Default
    private String apiKey = "demo-api-key";

    /**
     * 业务租户 ID (可选)
     */
    @Builder.Default
    private String tenantId = "tenant-sample";

    /**
     * 请求目标大模型名称
     */
    @NotBlank(message = "模型名称 modelName 不能为空")
    @Builder.Default
    private String modelName = "gpt-4o";

    /**
     * 预计消耗的 Token 数量 (必须为正整数)
     */
    @NotNull(message = "预估消耗 Token 数量不能为空")
    @Positive(message = "预估消耗 Token 数量必须大于 0")
    @Builder.Default
    private Integer tokens = 100;
}
