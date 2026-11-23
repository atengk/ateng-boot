package io.github.atengk.boot.sample.redis.model.traffic;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 流控判定结果视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitResultVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String key;
    private Boolean allowed;
    private Long remainingPermits;
}
