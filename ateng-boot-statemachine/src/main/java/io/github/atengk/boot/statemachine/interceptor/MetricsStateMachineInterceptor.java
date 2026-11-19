package io.github.atengk.boot.statemachine.interceptor;

import io.github.atengk.boot.statemachine.core.StateMachineInterceptor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/**
 * 状态机 Micrometer 性能度量监控拦截器
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class MetricsStateMachineInterceptor implements StateMachineInterceptor<Object, Object, Object> {

    private final MeterRegistry meterRegistry;

    public MetricsStateMachineInterceptor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public MeterRegistry getMeterRegistry() {
        return meterRegistry;
    }

    @Override
    public void afterTransition(String machineId, Object from, Object to, Object event, Object context, Duration elapsed) {
        if (meterRegistry == null) {
            return;
        }
        String resolvedMachineId = StateMachineInterceptor.resolveMachineId(machineId);
        recordCounter(resolvedMachineId, from, to, event, "success");

        if (elapsed != null) {
            Timer.builder("statemachine.transition.timer")
                    .description("Latency of state machine transitions")
                    .tag("machine_id", resolvedMachineId)
                    .tag("from", String.valueOf(from))
                    .tag("event", String.valueOf(event))
                    .tag("status", "success")
                    .register(meterRegistry)
                    .record(elapsed);
        }
    }

    @Override
    public void afterTransition(Object from, Object to, Object event, Object context, Duration elapsed) {
        afterTransition(null, from, to, event, context, elapsed);
    }

    @Override
    public void onException(String machineId, Object from, Object event, Object context, Exception ex) {
        if (meterRegistry == null) {
            return;
        }
        recordCounter(StateMachineInterceptor.resolveMachineId(machineId), from, "ERROR", event, "failure");
    }

    @Override
    public void onException(Object from, Object event, Object context, Exception ex) {
        onException(null, from, event, context, ex);
    }

    private void recordCounter(String machineId, Object from, Object to, Object event, String status) {
        Counter.builder("statemachine.transition.total")
                .description("Total number of state machine transitions")
                .tag("machine_id", machineId)
                .tag("from", String.valueOf(from))
                .tag("to", String.valueOf(to))
                .tag("event", String.valueOf(event))
                .tag("status", status)
                .register(meterRegistry)
                .increment();
    }
}
