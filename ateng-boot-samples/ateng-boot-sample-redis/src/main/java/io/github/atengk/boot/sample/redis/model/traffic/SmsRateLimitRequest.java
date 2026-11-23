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
 * 短信滑动窗口限流请求对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmsRateLimitRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "手机号不能为空")
    private String phoneNumber;

    @NotNull(message = "最大允许次数不能为空")
    @Min(value = 1, message = "最大允许次数必须大于 0")
    private Integer maxAttempts;

    @NotNull(message = "时间窗口秒数不能为空")
    @Min(value = 1, message = "时间窗口秒数必须大于 0")
    private Integer windowSeconds;
}
