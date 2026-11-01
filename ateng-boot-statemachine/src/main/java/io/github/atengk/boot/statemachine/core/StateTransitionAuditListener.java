package io.github.atengk.boot.statemachine.core;

import io.github.atengk.boot.statemachine.model.TransitionAuditEvent;

/**
 * 专职状态流转轨迹审计监听器 SPI 契约
 * <p>用于业务方异步持久化流转轨迹审计日志、全链路行为追踪与外部数仓上报</p>
 *
 * @param <S> 业务状态类型
 * @param <E> 触发事件类型
 * @param <C> 业务上下文类型
 * @author Ateng
 * @since 2026-11-01
 */
@FunctionalInterface
public interface StateTransitionAuditListener<S, E, C> {

    /**
     * 状态跃迁完成回调通知（跃迁成功或异常均会触发）
     *
     * @param event 结构化状态跃迁审计事件对象
     */
    void onTransitionCompleted(TransitionAuditEvent<S, E, C> event);
}
