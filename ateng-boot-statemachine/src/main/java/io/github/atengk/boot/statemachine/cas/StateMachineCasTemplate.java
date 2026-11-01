package io.github.atengk.boot.statemachine.cas;

import io.github.atengk.boot.statemachine.core.StateMachine;
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
        return stateMachine.fireEventWithCas(entity, getter, setter, event, context, casUpdater);
    }

    /**
     * 执行带 CAS 乐观锁防重的状态跃迁（免上下文 Fail-Fast 重载）
     *
     * @param stateMachine 状态机实例
     * @param entity       业务实体
     * @param getter       源状态提取器
     * @param setter       新状态回写器
     * @param event        触发事件
     * @param casUpdater   CAS 持久化更新器
     * @param <S>          状态类型
     * @param <E>          事件类型
     * @param <C>          上下文类型
     * @param <T>          实体类型
     * @return 跃迁并成功持久化后的目标状态
     */
    public static <S, E, C, T> S execute(
            StateMachine<S, E, C> stateMachine,
            T entity,
            Function<T, S> getter,
            BiConsumer<T, S> setter,
            E event,
            CasUpdater<T, S> casUpdater) {

        return execute(stateMachine, entity, getter, setter, event, null, casUpdater);
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
        return stateMachine.fireEventWithCasQuietly(entity, getter, setter, event, context, casUpdater);
    }

    /**
     * 执行带 CAS 乐观锁防重的状态跃迁（免上下文静默探测模式重载）
     *
     * @param stateMachine 状态机实例
     * @param entity       业务实体
     * @param getter       源状态提取器
     * @param setter       新状态回写器
     * @param event        触发事件
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
            CasUpdater<T, S> casUpdater) {

        return executeQuietly(stateMachine, entity, getter, setter, event, null, casUpdater);
    }
}
