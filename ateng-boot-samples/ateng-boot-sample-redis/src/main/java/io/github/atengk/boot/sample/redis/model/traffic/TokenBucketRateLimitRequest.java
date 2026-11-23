package io.github.atengk.boot.sample.redis.model.traffic;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 令牌桶流控请求对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenBucketRateLimitRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "API Key 不能为空")
    private String apiKey;

    @NotNull(message = "速率不能为空")
    @Min(value = 1, message = "速率必须大于 0")
    private Long rate;

    @NotNull(message = "间隔秒数不能为空")
    @Min(value = 1, message = "间隔秒数必须大于 0")
    private Long intervalSeconds;
}
