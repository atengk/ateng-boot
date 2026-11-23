package io.github.atengk.boot.sample.smoke.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * Redis 双模探测状态响应 VO
 *
 * @author Ateng
 * @since 2026-11-12
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SmokeRedisProbeVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Redis 基础设施是否处于激活可用状态
     */
    private boolean redisAvailable;

    /**
     * 当前运行模式（OFFLINE_DEGRADED / LOCAL_LIVE）
     */
    private String mode;

    /**
     * 探测键名
     */
    private String key;

    /**
     * 回显探测值
     */
    private String value;
}
