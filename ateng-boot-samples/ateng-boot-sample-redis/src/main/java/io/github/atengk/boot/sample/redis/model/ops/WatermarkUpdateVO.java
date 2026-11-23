package io.github.atengk.boot.sample.redis.model.ops;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 水位更新响应视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WatermarkUpdateVO {

    private String key;
    private Long expect;
    private Long update;
    private Boolean success;
    private Long currentVal;
}
