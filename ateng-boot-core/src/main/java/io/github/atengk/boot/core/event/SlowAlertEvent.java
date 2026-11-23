package io.github.atengk.boot.core.event;

import io.github.atengk.boot.core.aspect.SlowAlertAspect;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;

/**
 * 方法级慢调用告警领域事件
 * 当标注有 {@link io.github.atengk.boot.core.annotation.SlowAlert} 的方法执行耗时达到或超过阈值时，
 * 由 {@link SlowAlertAspect} 通过 Spring 事件总线发布此不可变事件，供业务侧异步落库、指标上报或告警通知解耦消费
 *
 * @author Ateng
 * @since 2026-11-14
 */
public class SlowAlertEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    /**
     * 目标类全限定类名
     */
    private final String className;

    /**
     * 目标方法名称
     */
    private final String methodName;

    /**
     * 实际执行耗时（毫秒）
     */
    private final long elapsedMs;

    /**
     * 生效的慢调用告警阈值（毫秒）
     */
    private final long thresholdMs;

    /**
     * 经过脱敏掩码与长度截断后的入参摘要（未开启入参采集时为空字符串）
     */
    private final String argsSummary;

    /**
     * 当前全链路追踪标识（来自 MDC {@code traceId}，无上下文时为空字符串）
     */
    private final String traceId;

    /**
     * 慢调用事件触发时间
     */
    private final LocalDateTime occurredAt;

    /**
     * 构建不可变慢调用告警领域事件
     *
     * @param source      事件发布源对象
     * @param className   目标类全限定类名
     * @param methodName  目标方法名称
     * @param elapsedMs   实际执行耗时（毫秒）
     * @param thresholdMs 慢调用告警阈值（毫秒）
     * @param argsSummary 脱敏截断后的入参摘要
     * @param traceId     全链路追踪标识
     * @param occurredAt  事件触发时间
     */
    public SlowAlertEvent(Object source,
                          String className,
                          String methodName,
                          long elapsedMs,
                          long thresholdMs,
                          String argsSummary,
                          String traceId,
                          LocalDateTime occurredAt) {
        super(source != null ? source : SlowAlertEvent.class);
        this.className = className != null ? className : "";
        this.methodName = methodName != null ? methodName : "";
        this.elapsedMs = Math.max(0L, elapsedMs);
        this.thresholdMs = Math.max(0L, thresholdMs);
        this.argsSummary = argsSummary != null ? argsSummary : "";
        this.traceId = traceId != null ? traceId : "";
        this.occurredAt = occurredAt != null ? occurredAt : LocalDateTime.now();
    }

    /**
     * 获取目标类全限定类名
     *
     * @return 目标类名
     */
    public String getClassName() {
        return className;
    }

    /**
     * 获取目标方法名称
     *
     * @return 目标方法名
     */
    public String getMethodName() {
        return methodName;
    }

    /**
     * 获取实际执行耗时（毫秒）
     *
     * @return 实际耗时
     */
    public long getElapsedMs() {
        return elapsedMs;
    }

    /**
     * 获取慢调用告警阈值（毫秒）
     *
     * @return 生效阈值
     */
    public long getThresholdMs() {
        return thresholdMs;
    }

    /**
     * 获取脱敏截断后的入参摘要
     *
     * @return 入参摘要
     */
    public String getArgsSummary() {
        return argsSummary;
    }

    /**
     * 获取当前全链路追踪标识
     *
     * @return 全链路追踪标识
     */
    public String getTraceId() {
        return traceId;
    }

    /**
     * 获取慢调用事件触发时间
     *
     * @return 触发时间
     */
    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    @Override
    public String toString() {
        return "SlowAlertEvent{" +
                "className='" + className + '\'' +
                ", methodName='" + methodName + '\'' +
                ", elapsedMs=" + elapsedMs +
                ", thresholdMs=" + thresholdMs +
                ", argsSummary='" + argsSummary + '\'' +
                ", traceId='" + traceId + '\'' +
                ", occurredAt=" + occurredAt +
                '}';
    }
}
