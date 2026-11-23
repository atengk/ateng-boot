package io.github.atengk.boot.sample.admin.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Duration;

/**
 * 僵尸实例清理扫描结果响应 VO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
public class ZombieCleanupResultVO {

    /**
     * 本次扫描驱逐注销的僵尸实例数量
     */
    private int evictedCount;

    /**
     * 清理调度器是否处于运行状态
     */
    private boolean schedulerRunning;

    /**
     * 本次扫描生效的僵尸超期阈值
     */
    private Duration zombieThreshold;

    /**
     * 清理完成后剩余处于已注册状态的实例数量
     */
    private long remainingRegisteredCount;
}
