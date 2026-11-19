package io.github.atengk.boot.sample.datasource.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 五维异步上下文跨线程透传验证请求传输对象
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsyncContextPropagateRequest {

    /**
     * 链路追踪标识（可选；未传时沿用当前 HTTP 请求 MDC traceId）
     */
    private String traceId;

    /**
     * 操作用户标识
     */
    @NotBlank(message = "用户标识不能为空")
    private String userId;

    /**
     * 租户标识
     */
    @NotBlank(message = "租户标识不能为空")
    private String tenantId;

    /**
     * 是否忽略租户过滤标志
     */
    @Builder.Default
    private boolean ignoreTenant = false;

    /**
     * 目标数据源标识
     */
    @NotBlank(message = "数据源标识不能为空")
    private String dsKey;
}
