package io.github.atengk.boot.sample.job.local.vo;

import io.github.atengk.boot.job.local.model.JobExecutionRecord;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * 任务执行审计历史记录视图对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobExecutionRecordVO implements Serializable {

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
     * 触发类型描述
     */
    private String triggerType;

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
     * 重试执行次数
     */
    private int retryCount;

    /**
     * 任务实时打印的执行日志清单
     */
    private List<String> logs;

    /**
     * 从内核模型转换为视图对象
     *
     * @param record 内核 JobExecutionRecord 对象
     * @return 转换后的 VO
     */
    public static JobExecutionRecordVO from(JobExecutionRecord record) {
        if (record == null) {
            return null;
        }
        return JobExecutionRecordVO.builder()
                .id(record.getId())
                .jobName(record.getJobName())
                .traceId(record.getTraceId())
                .triggerType(record.getTriggerType() != null ? record.getTriggerType().name() : null)
                .fireTime(record.getFireTime())
                .finishTime(record.getFinishTime())
                .durationMs(record.getDurationMs())
                .status(record.getStatus())
                .resultMsg(record.getResultMsg())
                .errorMessage(record.getErrorMessage())
                .retryCount(record.getRetryCount())
                .logs(record.getLogs() != null ? record.getLogs() : Collections.emptyList())
                .build();
    }
}
