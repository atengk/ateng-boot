package io.github.atengk.boot.sample.redis.model.ops;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 水位 CAS 替换请求参数
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WatermarkCasRequest {

    @NotBlank(message = "水位键不能为空")
    private String key;

    @NotNull(message = "期望旧值不能为空")
    private Long expect;

    @NotNull(message = "目标新值不能为空")
    private Long update;
}
