package io.github.atengk.boot.sample.ai.dto;

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
     * 请求目标大模型名称 (可选)
     */
    @Builder.Default
    private String modelName = "gpt-4o";

    /**
     * 预计消耗的 Token 数量 (可选，默认 100)
     */
    @Builder.Default
    private Integer tokens = 100;
}
