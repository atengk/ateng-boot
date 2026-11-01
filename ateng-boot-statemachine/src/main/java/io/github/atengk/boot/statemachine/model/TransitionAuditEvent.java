package io.github.atengk.boot.statemachine.model;

import java.time.Duration;

/**
 * 状态机跃迁轨迹审计事件对象
 *
 * @param <S> 业务状态类型
 * @param <E> 触发事件类型
 * @param <C> 业务上下文类型
 * @author Ateng
 * @since 2026-11-01
 */
public class TransitionAuditEvent<S, E, C> {

    private final String machineId;
    private final S fromState;
    private final S toState;
    private final E event;
    private final C context;
    private final String traceId;
    private final String operator;
    private final Duration elapsed;
    private final boolean success;
    private final Throwable cause;

    public TransitionAuditEvent(String machineId, S fromState, S toState, E event, C context, String traceId, String operator, Duration elapsed, boolean success, Throwable cause) {
        this.machineId = machineId;
        this.fromState = fromState;
        this.toState = toState;
        this.event = event;
        this.context = context;
        this.traceId = traceId;
        this.operator = operator;
        this.elapsed = elapsed != null ? elapsed : Duration.ZERO;
        this.success = success;
        this.cause = cause;
    }

    public TransitionAuditEvent(String machineId, S fromState, S toState, E event, C context, String traceId, Duration elapsed, boolean success, Throwable cause) {
        this(machineId, fromState, toState, event, context, traceId, null, elapsed, success, cause);
    }

    public static <S, E, C> Builder<S, E, C> builder() {
        return new Builder<>();
    }

    public String getMachineId() {
        return machineId;
    }

    public S getFromState() {
        return fromState;
    }

    public S getToState() {
        return toState;
    }

    public E getEvent() {
        return event;
    }

    public C getContext() {
        return context;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getOperator() {
        return operator;
    }

    public Duration getElapsed() {
        return elapsed;
    }

    public boolean isSuccess() {
        return success;
    }

    public Throwable getCause() {
        return cause;
    }

    @Override
    public String toString() {
        return "TransitionAuditEvent{" +
                "machineId='" + machineId + '\'' +
                ", fromState=" + fromState +
                ", toState=" + toState +
                ", event=" + event +
                ", traceId='" + traceId + '\'' +
                ", operator='" + operator + '\'' +
                ", elapsed=" + elapsed.toMillis() + "ms" +
                ", success=" + success +
                ", cause=" + (cause != null ? cause.getMessage() : "null") +
                '}';
    }

    public static class Builder<S, E, C> {
        private String machineId;
        private S fromState;
        private S toState;
        private E event;
        private C context;
        private String traceId;
        private String operator;
        private Duration elapsed;
        private boolean success;
        private Throwable cause;

        public Builder<S, E, C> machineId(String machineId) {
            this.machineId = machineId;
            return this;
        }

        public Builder<S, E, C> fromState(S fromState) {
            this.fromState = fromState;
            return this;
        }

        public Builder<S, E, C> toState(S toState) {
            this.toState = toState;
            return this;
        }

        public Builder<S, E, C> event(E event) {
            this.event = event;
            return this;
        }

        public Builder<S, E, C> context(C context) {
            this.context = context;
            return this;
        }

        public Builder<S, E, C> traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder<S, E, C> operator(String operator) {
            this.operator = operator;
            return this;
        }

        public Builder<S, E, C> elapsed(Duration elapsed) {
            this.elapsed = elapsed;
            return this;
        }

        public Builder<S, E, C> success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder<S, E, C> cause(Throwable cause) {
            this.cause = cause;
            return this;
        }

        public TransitionAuditEvent<S, E, C> build() {
            return new TransitionAuditEvent<>(machineId, fromState, toState, event, context, traceId, operator, elapsed, success, cause);
        }
    }
}
