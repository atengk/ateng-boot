package io.github.atengk.boot.sample.statemachine.listener;

import io.github.atengk.boot.sample.statemachine.constant.OrderEvent;
import io.github.atengk.boot.sample.statemachine.constant.OrderState;
import io.github.atengk.boot.sample.statemachine.context.OrderContext;
import io.github.atengk.boot.statemachine.core.StateTransitionAuditListener;
import io.github.atengk.boot.statemachine.model.TransitionAuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 订单状态机流转轨迹审计监听器业务实现
 * <p>Spring 受管组件，由 StateMachineInitializer 自动探测并注册至状态机机台</p>
 *
 * @author Ateng
 * @since 2026-11-01
 */
@Component
public class OrderTransitionAuditLogger implements StateTransitionAuditListener<OrderState, OrderEvent, OrderContext> {

    private static final Logger log = LoggerFactory.getLogger(OrderTransitionAuditLogger.class);

    private final List<TransitionAuditEvent<OrderState, OrderEvent, OrderContext>> auditEvents = new CopyOnWriteArrayList<>();

    @Override
    public void onTransitionCompleted(TransitionAuditEvent<OrderState, OrderEvent, OrderContext> event) {
        auditEvents.add(event);
        log.info("[Audit] 状态机 [{}] 跃迁审计: from={} -> to={}, event={}, success={}, traceId={}, 耗时={}ms",
                event.getMachineId(), event.getFromState(), event.getToState(), event.getEvent(),
                event.isSuccess(), event.getTraceId(), event.getElapsed().toMillis());
    }

    public List<TransitionAuditEvent<OrderState, OrderEvent, OrderContext>> getAuditEvents() {
        return Collections.unmodifiableList(auditEvents);
    }

    public void clear() {
        auditEvents.clear();
    }
}
