package io.github.atengk.boot.sample.redis.model.ops;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Pipeline 预热完成响应视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PipelineWarmupVO {

    private Integer totalItems;
    private Long elapsedMs;
    private Boolean success;
}
