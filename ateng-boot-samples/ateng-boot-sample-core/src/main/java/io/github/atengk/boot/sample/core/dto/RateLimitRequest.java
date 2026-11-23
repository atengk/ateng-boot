package io.github.atengk.boot.sample.core.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 接口防刷与流量整形请求对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 客户端标识
     */
    @NotBlank(message = "客户端标识不能为空")
    private String clientId;

    /**
     * 业务防重幂等 Token / 流水号
     */
    @NotBlank(message = "幂等Token不能为空")
    private String reqToken;

    /**
     * 是否模拟触发幂等防重检测（若为 true 且 reqToken 已存在，则抛出 IdempotentException）
     */
    private boolean triggerIdempotentCheck;
}
