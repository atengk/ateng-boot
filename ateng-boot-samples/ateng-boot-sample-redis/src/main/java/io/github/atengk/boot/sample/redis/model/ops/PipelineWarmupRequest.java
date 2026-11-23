package io.github.atengk.boot.sample.redis.model.ops;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Pipeline 批量库存预热请求参数
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PipelineWarmupRequest {

    @NotEmpty(message = "预热库存映射不能为空")
    private Map<String, Integer> stockMap;
}
