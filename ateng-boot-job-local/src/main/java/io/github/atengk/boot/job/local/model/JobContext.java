package io.github.atengk.boot.job.local.model;

import io.github.atengk.boot.job.local.enums.TriggerType;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 任务运行时执行上下文
 * <p>
 * 在任务方法执行时作为参数传入，承载本次调度的元数据、参数、MDC 追踪号与实时日志收集。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
@Builder
@ToString
public class JobContext {

    /**
     * 任务名称
     */
    private final String jobName;

    /**
     * 本次调度的触发类型
     */
    private final TriggerType triggerType;

    /**
     * 本次任务触发时间戳
     */
    private final Instant fireTime;

    /**
     * 外部手动触发传入的业务参数（不可变）
     */
    @Builder.Default
    private final Map<String, Object> params = Collections.emptyMap();

    /**
     * 本次执行绑定的链路追踪号
     */
    private final String traceId;

    /**
     * 本次调度的实时执行日志收集缓冲
     */
    @Builder.Default
    private final List<String> logs = new CopyOnWriteArrayList<>();

    /**
     * 记录本次执行的临时业务日志
     *
     * @param message 日志内容
     */
    public void log(String message) {
        if (message != null) {
            logs.add("[" + Instant.now() + "] " + message);
        }
    }
}
