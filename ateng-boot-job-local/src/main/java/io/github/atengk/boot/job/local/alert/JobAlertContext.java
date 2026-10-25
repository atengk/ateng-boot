package io.github.atengk.boot.job.local.alert;

import io.github.atengk.boot.job.local.enums.TriggerType;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;

/**
 * 任务告警上下文数据对象
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
@Builder
@ToString
public class JobAlertContext {

    public static final String ALERT_TYPE_FAILED = "FAILED";
    public static final String ALERT_TYPE_TIMEOUT = "TIMEOUT";

    /**
     * 任务名称
     */
    private final String jobName;

    /**
     * 触发类型
     */
    private final TriggerType triggerType;

    /**
     * 全链路追踪 traceId
     */
    private final String traceId;

    /**
     * 告警类型（FAILED: 执行失败, TIMEOUT: 硬超时熔断）
     */
    private final String alertType;

    /**
     * 执行耗时（毫秒）
     */
    private final long durationMs;

    /**
     * 实际退避重试次数
     */
    private final int retryCount;

    /**
     * 错误摘要信息
     */
    private final String errorMsg;

    /**
     * 根因异常（若有）
     */
    private final Throwable throwable;

    /**
     * 告警发生时间戳
     */
    private final Instant timestamp;
}
