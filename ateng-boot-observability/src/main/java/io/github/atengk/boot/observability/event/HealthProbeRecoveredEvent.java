package io.github.atengk.boot.observability.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 健康探针故障恢复自愈领域事件
 * <p>
 * 与 {@link HealthProbeDegradedEvent} 构成对称生命周期契约：当健康探针由故障态（{@code DOWN} 或 {@code TIMEOUT}）
 * 恢复为健康态（{@code UP}）时发布，携带恢复前故障状态、持续故障时长与累计连续失败次数，便于告警平台自动消警闭环。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Getter
public class HealthProbeRecoveredEvent extends ApplicationEvent {

    /**
     * 恢复的探针类简名
     */
    private final String probeName;

    /**
     * 恢复的业务组件名称（如 paymentGateway、redis、kafka 等）
     */
    private final String componentName;

    /**
     * 恢复前最近一次观测到的故障状态（DOWN / TIMEOUT）
     */
    private final String previousStatus;

    /**
     * 本次故障持续总时长（毫秒）
     */
    private final long downtimeMs;

    /**
     * 本次故障周期内累计连续失败次数
     */
    private final long consecutiveFailures;

    /**
     * 构造健康探针恢复自愈事件
     *
     * @param source              事件源
     * @param probeName           探针类简名
     * @param componentName       组件名称
     * @param previousStatus      恢复前故障状态
     * @param downtimeMs          持续故障时长（毫秒）
     * @param consecutiveFailures 累计连续失败次数
     */
    public HealthProbeRecoveredEvent(
            Object source,
            String probeName,
            String componentName,
            String previousStatus,
            long downtimeMs,
            long consecutiveFailures) {
        super(source);
        this.probeName = probeName;
        this.componentName = componentName;
        this.previousStatus = previousStatus;
        this.downtimeMs = downtimeMs;
        this.consecutiveFailures = consecutiveFailures;
    }

    @Override
    public String toString() {
        return "HealthProbeRecoveredEvent{" +
                "probeName='" + probeName + '\'' +
                ", componentName='" + componentName + '\'' +
                ", previousStatus='" + previousStatus + '\'' +
                ", downtimeMs=" + downtimeMs +
                ", consecutiveFailures=" + consecutiveFailures +
                ", timestamp=" + getTimestamp() +
                '}';
    }
}
