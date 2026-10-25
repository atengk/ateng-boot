package io.github.atengk.boot.job.local.model;

import io.github.atengk.boot.job.local.enums.JobStatus;
import io.github.atengk.boot.job.local.enums.OverlappingStrategy;
import io.github.atengk.boot.job.local.enums.TriggerType;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.Duration;
import java.time.Instant;

/**
 * 任务运行时状态详情视图对象
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
@Setter
@Builder
@ToString
public class JobInfo {

    /**
     * 任务唯一标识名称
     */
    private String jobName;

    /**
     * 触发类型
     */
    private TriggerType triggerType;

    /**
     * 运行时生命周期状态
     */
    private JobStatus status;

    /**
     * 当前生效的 Cron 表达式
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
    private OverlappingStrategy strategy;

    /**
     * 单次任务硬超时时长
     */
    private Duration timeout;

    /**
     * 失败最大重试次数
     */
    private int maxRetries;

    /**
     * 是否开启集群互斥
     */
    private boolean clusterLock;

    /**
     * 任务业务描述
     */
    private String description;

    /**
     * 下次计划触发时间戳
     */
    private Instant nextExecutionTime;

    /**
     * 上次开始触发时间戳
     */
    private Instant lastExecutionTime;

    /**
     * 上次执行总耗时（毫秒）
     */
    private Long lastDurationMs;

    /**
     * 上次执行状态（SUCCESS / FAILED / DISCARDED / TIMEOUT 等）
     */
    private String lastStatus;

    /**
     * 上次执行返回成果摘要
     */
    private String lastResultMsg;
}
