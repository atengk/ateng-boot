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
 * <p>严格保证 CAS-First 安全时序：试算目标状态与卫语句 -> 执行数据库 CAS 更新 -> 冲突熔断/回写实体 -> 执行 Action 动作链，杜绝并发冲突下的虚假副作用</p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
public final class StateMachineCasTemplate {

    private StateMachineCasTemplate() {
    }

    /**
     * 执行带 CAS 乐观锁防重的状态跃迁（Fail-Fast 模式）
     * <p>按序执行生命周期：1. 试算目标状态与卫语句校验 -> 2. 执行数据库 CAS 乐观锁更新 -> 3. 校验影响行数（0 则并发冲突熔断） -> 4. 同步实体状态 -> 5. 执行动作链</p>
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
        // 1. 试算目标状态与校验卫语句（纯只读无副作用预检，不触发任何 Action 与持久层操作）
        TransitionResult<S> probe = stateMachine.verifyTransition(sourceState, event, context);
        if (!probe.isAccepted()) {
            StateMachineResultCode resultCode = (probe.getFailureReason() != null && probe.getFailureReason().contains("未找到"))
                    ? StateMachineResultCode.TRANSITION_NOT_FOUND
                    : StateMachineResultCode.CONDITION_FAILED;
            throw new StateMachineException(
                    resultCode,
                    sourceState,
                    event,
                    probe.getFailureReason(),
                    probe.getCause()
            );
        }

        S targetState = probe.getTargetState();

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

        // 4. CAS 成功后有序执行 Action 动作链
        stateMachine.executeActions(sourceState, targetState, event, context);

        return targetState;
    }

    /**
     * 执行带 CAS 乐观锁防重的状态跃迁（静默探测模式）
     * <p>按序执行生命周期：1. 试算目标状态与卫语句校验 -> 2. 执行数据库 CAS 乐观锁更新 -> 3. 校验影响行数（0 则并发冲突返回失败结果） -> 4. 同步实体状态 -> 5. 执行动作链</p>
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
        TransitionResult<S> probe = stateMachine.verifyTransition(sourceState, event, context);
        if (!probe.isAccepted()) {
            return probe;
        }

        S targetState = probe.getTargetState();
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

        try {
            stateMachine.executeActions(sourceState, targetState, event, context);
        } catch (Exception ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            return TransitionResult.failure(
                    sourceState,
                    event,
                    String.format("状态机 [%s] 跃迁动作执行失败 [%s -> %s on %s]: %s",
                            stateMachine.getMachineId(), sourceState, targetState, event, ex.getMessage()),
                    cause
            );
        }

        return TransitionResult.success(sourceState, targetState, event);
    }
}
