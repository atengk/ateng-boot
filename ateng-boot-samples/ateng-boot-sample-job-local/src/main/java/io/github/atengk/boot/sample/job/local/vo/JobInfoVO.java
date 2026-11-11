package io.github.atengk.boot.sample.job.local.vo;

import io.github.atengk.boot.job.local.model.JobInfo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * 受管定时任务详情视图对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobInfoVO implements Serializable {

    /**
     * 任务唯一标识名称
     */
    private String jobName;

    /**
     * 触发类型描述
     */
    private String triggerType;

    /**
     * 运行时生命周期状态描述
     */
    private String status;

    /**
     * 当前生效的 Cron 表达式
     */
    private String cron;

    /**
     * 固定频率调度间隔文本
     */
    private String fixedRate;

    /**
     * 固定延迟调度间隔文本
     */
    private String fixedDelay;

    /**
     * 初始延迟启动时长文本
     */
    private String initialDelay;

    /**
     * 并发重叠防卫策略描述
     */
    private String strategy;

    /**
     * 单次任务硬超时时长文本
     */
    private String timeout;

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
     * 上次执行的 MDC 链路追踪 ID
     */
    private String lastTraceId;

    /**
     * 上次执行总耗时（毫秒）
     */
    private Long lastDurationMs;

    /**
     * 上次执行状态
     */
    private String lastStatus;

    /**
     * 上次执行返回成果摘要
     */
    private String lastResultMsg;

    /**
     * 当前是否正在 Worker 线程池中处于活跃执行状态
     */
    private boolean executing;

    /**
     * SaaS 多租户调度模态描述
     */
    private String tenantMode;

    /**
     * 从内核模型转换为视图对象
     *
     * @param info 内核 JobInfo 对象
     * @return 转换后的 VO
     */
    public static JobInfoVO from(JobInfo info) {
        if (info == null) {
            return null;
        }
        return JobInfoVO.builder()
                .jobName(info.getJobName())
                .triggerType(info.getTriggerType() != null ? info.getTriggerType().name() : null)
                .status(info.getStatus() != null ? info.getStatus().name() : null)
                .cron(info.getCron())
                .fixedRate(info.getFixedRate() != null ? info.getFixedRate().toString() : null)
                .fixedDelay(info.getFixedDelay() != null ? info.getFixedDelay().toString() : null)
                .initialDelay(info.getInitialDelay() != null ? info.getInitialDelay().toString() : null)
                .strategy(info.getStrategy() != null ? info.getStrategy().name() : null)
                .timeout(info.getTimeout() != null ? info.getTimeout().toString() : null)
                .maxRetries(info.getMaxRetries())
                .clusterLock(info.isClusterLock())
                .description(info.getDescription())
                .nextExecutionTime(info.getNextExecutionTime())
                .lastExecutionTime(info.getLastExecutionTime())
                .lastTraceId(info.getLastTraceId())
                .lastDurationMs(info.getLastDurationMs())
                .lastStatus(info.getLastStatus())
                .lastResultMsg(info.getLastResultMsg())
                .executing(info.isExecuting())
                .tenantMode(info.getTenantMode() != null ? info.getTenantMode().name() : null)
                .build();
    }
}
