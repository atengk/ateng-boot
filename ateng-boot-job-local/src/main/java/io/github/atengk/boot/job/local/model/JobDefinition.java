package io.github.atengk.boot.job.local.model;

import io.github.atengk.boot.job.local.enums.JobStatus;
import io.github.atengk.boot.job.local.enums.OverlappingStrategy;
import io.github.atengk.boot.job.local.enums.TriggerType;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.Duration;

/**
 * 任务静态元数据定义对象
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
@Setter
@Builder
@ToString
public class JobDefinition {

    /**
     * 任务唯一标识名称
     */
    private String jobName;

    /**
     * 触发类型
     */
    private TriggerType triggerType;

    /**
     * Cron 调度表达式
     */
    private String cron;

    /**
     * 固定频率调度间隔
     */
    private Duration fixedRate;

    /**
     * 固定延迟调度间隔
     */
    private Duration fixedDelay;

    /**
     * 初始延迟启动时长
     */
    private Duration initialDelay;

    /**
     * 并发重叠防卫策略
     */
    @Builder.Default
    private OverlappingStrategy strategy = OverlappingStrategy.DISCARD;

    /**
     * 单次任务硬超时时长
     */
    private Duration timeout;

    /**
     * 失败重试次数
     */
    @Builder.Default
    private int maxRetries = 0;

    /**
     * 重试退避间隔
     */
    private Duration retryInterval;

    /**
     * 是否开启集群互斥锁
     */
    @Builder.Default
    private boolean clusterLock = false;

    /**
     * 集群互斥锁租约时长
     */
    private Duration clusterLockLeaseTime;

    /**
     * 任务业务描述
     */
    private String description;

    /**
     * 静态启用状态
     */
    @Builder.Default
    private boolean enabled = true;

    /**
     * 运行时生命周期状态
     */
    @Builder.Default
    private JobStatus status = JobStatus.RUNNING;
}
