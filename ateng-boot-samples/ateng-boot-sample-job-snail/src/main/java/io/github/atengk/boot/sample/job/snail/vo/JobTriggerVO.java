package io.github.atengk.boot.sample.job.snail.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 调度任务触发结果视图模型
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobTriggerVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 任务名称标识
     */
    private String jobName;

    /**
     * 执行结果状态 (SUCCESS / FAILURE / SKIPPED)
     */
    private String status;

    /**
     * 执行状态码 (1: 成功, 0: 失败)
     */
    private Integer statusCode;

    /**
     * 链路追踪 TraceId
     */
    private String traceId;

    /**
     * 执行耗时 (毫秒)
     */
    private Long elapsedMs;

    /**
     * 执行响应报文或诊断摘要
     */
    private String message;
}
