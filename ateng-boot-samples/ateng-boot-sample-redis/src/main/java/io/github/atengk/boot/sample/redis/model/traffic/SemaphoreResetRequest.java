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
 * 信号量配额动态调整请求对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemaphoreResetRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "信号量名称不能为空")
    private String semaphoreName;

    @NotNull(message = "新配额不能为空")
    @Min(value = 1, message = "新配额必须大于 0")
    private Integer newPermits;
}
