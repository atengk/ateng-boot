package io.github.atengk.boot.sample.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 线程池治理与监控状态响应视图对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PoolGovernanceVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标业务线程池名称
     */
    private String poolName;

    /**
     * 调参前核心线程数
     */
    private int beforeCorePoolSize;

    /**
     * 调参前最大线程数
     */
    private int beforeMaxPoolSize;

    /**
     * 调参后核心线程数
     */
    private int afterCorePoolSize;

    /**
     * 调参后最大线程数
     */
    private int afterMaxPoolSize;

    /**
     * 是否为虚拟线程执行器
     */
    private boolean isVirtual;

    /**
     * 队列总容量
     */
    private int queueCapacity;

    /**
     * 队列剩余容量
     */
    private int queueRemainingCapacity;

    /**
     * 累计完成任务数
     */
    private long completedTaskCount;
}
