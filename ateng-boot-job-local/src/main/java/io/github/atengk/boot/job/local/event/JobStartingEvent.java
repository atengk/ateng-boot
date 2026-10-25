package io.github.atengk.boot.job.local.event;

import io.github.atengk.boot.job.local.enums.TriggerType;
import lombok.Getter;

import java.util.Collections;
import java.util.Map;

/**
 * 任务开始执行事件
 * <p>
 * 任务通过并发防卫与集群锁抢占后、进入实际处理器逻辑前发布。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
public class JobStartingEvent extends JobEvent {

    /**
     * 业务传入参数
     */
    private final Map<String, Object> params;

    public JobStartingEvent(Object source, String jobName, TriggerType triggerType, String traceId, Map<String, Object> params) {
        super(source, jobName, triggerType, traceId);
        this.params = params != null ? Collections.unmodifiableMap(params) : Collections.emptyMap();
    }
}
