package io.github.atengk.boot.observability.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 健康探针探测超时或降级故障领域事件
 * <p>
 * 当健康探针发生超时（TIMEOUT）或抛出不可逆异常导致降级（DOWN/UNKNOWN）时发布，
 * 携带故障组件信息与耗时，便于下游告警系统（如 Notification 模块或外部钉钉/企微/飞书机器人）毫秒级感知。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Getter
public class HealthProbeDegradedEvent extends ApplicationEvent {

    private final String probeName;
    private final String componentName;
    private final String status;
    private final long costMs;
    private final String reason;
    private final Throwable cause;

    public HealthProbeDegradedEvent(
            Object source,
            String probeName,
            String componentName,
            String status,
            long costMs,
            String reason,
            Throwable cause) {
        super(source);
        this.probeName = probeName;
        this.componentName = componentName;
        this.status = status;
        this.costMs = costMs;
        this.reason = reason;
        this.cause = cause;
    }
}
