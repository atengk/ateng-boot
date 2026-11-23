package io.github.atengk.boot.sample.redis.model.lock;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 争锁超时降级结果视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LockFallbackVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String resourceId;
    private Boolean fallbackTriggered;
    private String result;
}
