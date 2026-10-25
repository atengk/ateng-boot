package io.github.atengk.boot.statemachine.cas;

import io.github.atengk.boot.statemachine.core.StateMachine;
import io.github.atengk.boot.statemachine.enums.StateMachineResultCode;
import io.github.atengk.boot.statemachine.exception.StateMachineException;
import io.github.atengk.boot.statemachine.model.TransitionResult;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 状态机 CAS (Compare-And-Swap) 乐观锁防重执行模板
 * <p>协同数据库条件更新 {@code UPDATE table SET status = ? WHERE id = ? AND status = ?}，受影响行数为 0 时判定为并发冲突</p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
public final class StateMachineCasTemplate {

    private StateMachineCasTemplate() {
    }

    /**
     * 执行带 CAS 乐观锁防重的状态跃迁（Fail-Fast 模式）
     *
     * @param stateMachine 状态机实例
     * @param entity       业务实体
     * @param getter       源状态提取器
     * @param setter       新状态回写器
     * @param event        触发事件
     * @param context      业务上下文
     * @param casUpdater   CAS 持久化更新器
     * @param <S>          状态类型
     * @param <E>          事件类型
     * @param <C>          上下文类型
     * @param <T>          实体类型
     * @return 跃迁并成功持久化后的目标状态
     * @throws StateMachineException 若跃迁失败或 CAS 受影响行数为 0 时抛出
     */
    public static <S, E, C, T> S execute(
            StateMachine<S, E, C> stateMachine,
            T entity,
            Function<T, S> getter,
            BiConsumer<T, S> setter,
            E event,
            C context,
            CasUpdater<T, S> casUpdater) {

        if (stateMachine == null) {
            throw new IllegalArgumentException("StateMachine must not be null");
        }
        if (entity == null) {
            throw new IllegalArgumentException("Target entity must not be null");
        }
        if (getter == null || setter == null) {
            throw new IllegalArgumentException("Getter and setter must not be null");
        }
        if (casUpdater == null) {
            throw new IllegalArgumentException("CasUpdater must not be null");
        }

        S sourceState = getter.apply(entity);
        // 1. 触发状态机跃迁（若路由不存在或卫语句校验不通过，此处直接抛出异常）
        S targetState = stateMachine.fireEvent(sourceState, event, context);

        // 2. 执行数据库 CAS 乐观锁条件更新
        int rowsAffected = casUpdater.updateWithCas(entity, sourceState, targetState);
        if (rowsAffected <= 0) {
            throw new StateMachineException(
                    StateMachineResultCode.CONCURRENT_CAS_CONFLICT,
                    sourceState,
                    targetState,
                    event,
                    String.format("状态机 [%s] 并发 CAS 乐观锁更新冲突 [实体状态由 %s 跃迁至 %s 失败，受影响行数为 0]",
                            stateMachine.getMachineId(), sourceState, targetState)
            );
        }

        // 3. CAS 成功后回写实体内存状态
        setter.accept(entity, targetState);
        return targetState;
    }

    /**
     * 执行带 CAS 乐观锁防重的状态跃迁（静默探测模式）
     *
     * @param stateMachine 状态机实例
     * @param entity       业务实体
     * @param getter       源状态提取器
     * @param setter       新状态回写器
     * @param event        触发事件
     * @param context      业务上下文
     * @param casUpdater   CAS 持久化更新器
     * @param <S>          状态类型
     * @param <E>          事件类型
     * @param <C>          上下文类型
     * @param <T>          实体类型
     * @return 结构化跃迁结果
     */
    public static <S, E, C, T> TransitionResult<S> executeQuietly(
            StateMachine<S, E, C> stateMachine,
            T entity,
            Function<T, S> getter,
            BiConsumer<T, S> setter,
            E event,
            C context,
            CasUpdater<T, S> casUpdater) {

        if (stateMachine == null) {
            return TransitionResult.failure(null, event, "StateMachine must not be null");
        }
        if (entity == null) {
            return TransitionResult.failure(null, event, "Target entity must not be null");
        }
        if (getter == null || setter == null) {
            throw new IllegalArgumentException("Getter and setter must not be null");
        }
        if (casUpdater == null) {
            throw new IllegalArgumentException("CasUpdater must not be null");
        }

        S sourceState = getter.apply(entity);
        TransitionResult<S> result = stateMachine.fireEventQuietly(sourceState, event, context);
        if (!result.isAccepted()) {
            return result;
        }

        S targetState = result.getTargetState();
        try {
            int rowsAffected = casUpdater.updateWithCas(entity, sourceState, targetState);
            if (rowsAffected <= 0) {
                return TransitionResult.failure(
                        sourceState,
                        event,
                        String.format("状态机 [%s] 并发 CAS 乐观锁更新冲突 [实体状态由 %s 跃迁至 %s 失败，受影响行数为 0]",
                                stateMachine.getMachineId(), sourceState, targetState)
                );
            }
        } catch (Exception ex) {
            return TransitionResult.failure(
                    sourceState,
                    event,
                    String.format("状态机 [%s] 执行 CAS 更新时发生异常: %s", stateMachine.getMachineId(), ex.getMessage()),
                    ex
            );
        }

        setter.accept(entity, targetState);
        return result;
    }
}
