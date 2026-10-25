package io.github.atengk.boot.job.local.event;

import io.github.atengk.boot.job.local.enums.TriggerType;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.Instant;

/**
 * 任务领域事件抽象基类
 * <p>
 * 继承 Spring 原生 {@link ApplicationEvent}，挂载任务名、触发方式、MDC 链路追踪标识与发生时间戳。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
public abstract class JobEvent extends ApplicationEvent {

    /**
     * 任务唯一标识名称
     */
    private final String jobName;

    /**
     * 本次执行触发类型
     */
    private final TriggerType triggerType;

    /**
     * 32 位全链路追踪 ID
     */
    private final String traceId;

    /**
     * 事件发生时间戳
     */
    private final Instant eventTime;

    public JobEvent(Object source, String jobName, TriggerType triggerType, String traceId) {
        super(source);
        this.jobName = jobName;
        this.triggerType = triggerType;
        this.traceId = traceId;
        this.eventTime = Instant.now();
    }
}
