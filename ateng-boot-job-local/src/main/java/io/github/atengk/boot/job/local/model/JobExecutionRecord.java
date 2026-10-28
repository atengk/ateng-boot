package io.github.atengk.boot.job.local.model;

import io.github.atengk.boot.job.local.enums.TriggerType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.List;

/**
 * 任务单次执行审计记录对象
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class JobExecutionRecord {

    /**
     * 记录唯一流水号
     */
    private String id;

    /**
     * 任务唯一名称
     */
    private String jobName;

    /**
     * 本次调度的 MDC 链路追踪 ID
     */
    private String traceId;

    /**
     * 触发类型
     */
    private TriggerType triggerType;

    /**
     * 调度开始触发时间戳
     */
    private Instant fireTime;

    /**
     * 调度结束完成时间戳
     */
    private Instant finishTime;

    /**
     * 调度总耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 执行终态（SUCCESS, FAILED, DISCARDED, TIMEOUT）
     */
    private String status;

    /**
     * 成果摘要或结果文本
     */
    private String resultMsg;

    /**
     * 异常摘要信息（若失败）
     */
    private String errorMessage;

    /**
     * 异常完整堆栈（若失败）
     */
    private String errorStack;

    /**
     * 重试执行次数
     */
    private int retryCount;

    /**
     * 任务实时打印的执行日志清单
     */
    private List<String> logs;
}
