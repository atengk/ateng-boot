package io.github.atengk.boot.sample.redis.model.traffic;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 令牌桶重置请求对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenBucketResetRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "API Key 不能为空")
    private String apiKey;
}
