package io.github.atengk.boot.statemachine.core;

import io.github.atengk.boot.core.util.BatchSliceUtil;
import io.github.atengk.boot.core.util.CollectionUtil;
import io.github.atengk.boot.statemachine.cas.CasUpdater;
import io.github.atengk.boot.statemachine.cas.StateMachineCasTemplate;
import io.github.atengk.boot.statemachine.enums.StateMachineResultCode;
import io.github.atengk.boot.statemachine.exception.StateMachineException;
import io.github.atengk.boot.statemachine.model.BatchOptions;
import io.github.atengk.boot.statemachine.model.BatchTransitionResult;
import io.github.atengk.boot.statemachine.model.TransitionResult;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 高性能无状态业务状态机核心门面契约
 *
 * @param <S> 业务状态类型
 * @param <E> 触发事件类型
 * @param <C> 业务上下文类型
 * @author Ateng
 * @since 2026-10-25
 */
public interface StateMachine<S, E, C> {

    /**
     * 获取当前状态机全局唯一标识
     *
     * @return 状态机标识
     */
    String getMachineId();

    /**
     * 触发状态跃迁（Fail-Fast 模式）
     * <p>若未找到匹配跃迁、卫语句不满足或 Action 执行异常，将直接抛出 {@link io.github.atengk.boot.statemachine.exception.StateMachineException}</p>
     *
     * @param sourceState 当前源状态
     * @param event       触发事件
     * @param context     业务上下文
     * @return 跃迁后的目标状态
     */
    S fireEvent(S sourceState, E event, C context);

    /**
     * 实体状态流转便捷门面（零侵入 Lambda 访问）
     * <p>自动通过 getter 提取当前状态并触发跃迁，成功后自动通过 setter 回写实体状态</p>
     *
     * @param entity  业务实体
     * @param getter  状态提取函数
     * @param setter  状态回写消费器
     * @param event   触发事件
     * @param context 业务上下文
     * @param <T>     业务实体类型
     * @return 跃迁后的目标状态
     */
    default <T> S fireEvent(T entity, Function<T, S> getter, BiConsumer<T, S> setter, E event, C context) {
        if (entity == null) {
            throw new IllegalArgumentException("Target entity must not be null");
        }
        if (getter == null || setter == null) {
            throw new IllegalArgumentException("Getter and setter must not be null");
        }
        S currentState = getter.apply(entity);
        S targetState = fireEvent(currentState, event, context);
        setter.accept(entity, targetState);
        return targetState;
    }

    /**
     * 大批量切片状态流转门面
     * <p>原生复用 Core 模块 BatchSliceUtil 进行分批切片，按批次执行状态流转</p>
     * <p>支持 Fail-Safe（记录失败持续推进）与 Fail-Fast（遭遇首个异常立即熔断中断）</p>
     *
     * @param entities 实体集合
     * @param getter   源状态提取函数
     * @param setter   新状态回写消费器
     * @param event    触发事件
     * @param context  业务上下文
     * @param options  分批配置选项
     * @param <T>      实体类型
     * @return 批量流转聚合结果
     */
    default <T> BatchTransitionResult<S, T> fireEventBatch(
            List<T> entities,
            Function<T, S> getter,
            BiConsumer<T, S> setter,
            E event,
            C context,
            BatchOptions options
    ) {
        if (getter == null || setter == null) {
            throw new IllegalArgumentException("Getter and setter must not be null");
        }
        if (event == null) {
            throw new IllegalArgumentException("Event must not be null");
        }
        BatchOptions opts = options != null ? options : BatchOptions.defaults();
        if (CollectionUtil.isEmpty(entities)) {
            return BatchTransitionResult.empty();
        }

        List<T> nonNullEntities = entities.stream().filter(Objects::nonNull).toList();
        if (nonNullEntities.isEmpty()) {
            return BatchTransitionResult.empty();
        }

        List<List<T>> slices = BatchSliceUtil.slice(nonNullEntities, opts.getBatchSize());
        List<T> successEntities = new ArrayList<>(nonNullEntities.size());
        Map<T, StateMachineException> failures = new LinkedHashMap<>();

        for (List<T> slice : slices) {
            for (T entity : slice) {
                S sourceState = getter.apply(entity);
                try {
                    S targetState = fireEvent(sourceState, event, context);
                    setter.accept(entity, targetState);
                    successEntities.add(entity);
                } catch (StateMachineException smEx) {
                    if (opts.isFailFast()) {
                        throw new StateMachineException(
                                StateMachineResultCode.BATCH_TRANSITION_FAILED,
                                sourceState,
                                smEx.getTargetState(),
                                event,
                                String.format("状态机 [%s] 大批量流转触发 Fail-Fast 熔断中断 [entity=%s]: %s",
                                        getMachineId(), entity, smEx.getMessage()),
                                smEx
                        );
                    }
                    failures.put(entity, smEx);
                } catch (Exception ex) {
                    StateMachineException smEx = new StateMachineException(
                            StateMachineResultCode.ACTION_EXECUTION_FAILED,
                            sourceState,
                            event,
                            String.format("状态机 [%s] 实体批量跃迁异常 [entity=%s]: %s",
                                    getMachineId(), entity, ex.getMessage()),
                            ex
                    );
                    if (opts.isFailFast()) {
                        throw new StateMachineException(
                                StateMachineResultCode.BATCH_TRANSITION_FAILED,
                                sourceState,
                                null,
                                event,
                                String.format("状态机 [%s] 大批量流转触发 Fail-Fast 熔断中断 [entity=%s]: %s",
                                        getMachineId(), entity, smEx.getMessage()),
                                smEx
                        );
                    }
                    failures.put(entity, smEx);
                }
            }
        }

        return BatchTransitionResult.of(nonNullEntities.size(), successEntities, failures);
    }

    /**
     * 大批量切片状态流转门面（默认分批配置：批次 200，Fail-Safe 模式）
     *
     * @param entities 实体集合
     * @param getter   源状态提取函数
     * @param setter   新状态回写消费器
     * @param event    触发事件
     * @param context  业务上下文
     * @param <T>      实体类型
     * @return 批量流转聚合结果
     */
    default <T> BatchTransitionResult<S, T> fireEventBatch(
            List<T> entities,
            Function<T, S> getter,
            BiConsumer<T, S> setter,
            E event,
            C context
    ) {
        return fireEventBatch(entities, getter, setter, event, context, BatchOptions.defaults());
    }

    /**
     * 大批量切片状态流转门面（免上下文重载）
     *
     * @param entities 实体集合
     * @param getter   源状态提取函数
     * @param setter   新状态回写消费器
     * @param event    触发事件
     * @param <T>      实体类型
     * @return 批量流转聚合结果
     */
    default <T> BatchTransitionResult<S, T> fireEventBatch(
            List<T> entities,
            Function<T, S> getter,
            BiConsumer<T, S> setter,
            E event
    ) {
        return fireEventBatch(entities, getter, setter, event, (C) null, BatchOptions.defaults());
    }

    /**
     * 大批量切片状态流转门面（带分批配置免上下文重载）
     *
     * @param entities 实体集合
     * @param getter   源状态提取函数
     * @param setter   新状态回写消费器
     * @param event    触发事件
     * @param options  分批配置选项
     * @param <T>      实体类型
     * @return 批量流转聚合结果
     */
    default <T> BatchTransitionResult<S, T> fireEventBatch(
            List<T> entities,
            Function<T, S> getter,
            BiConsumer<T, S> setter,
            E event,
            BatchOptions options
    ) {
        return fireEventBatch(entities, getter, setter, event, (C) null, options);
    }

    /**
     * 判断当前状态是否可接受指定事件（纯只读无副作用预检）
     * <p>仅求值流转路由与前置卫语句，100% 杜绝触发任何 Action 动作与持久层更新</p>
     *
     * @param sourceState 当前源状态
     * @param event       触发事件
     * @param context     业务上下文
     * @return 若存在匹配路由且卫语句满足则返回 true，否则返回 false（卫语句异常时安全返回 false）
     */
    boolean canFire(S sourceState, E event, C context);

    /**
     * 判断当前状态是否可接受指定事件（纯只读无副作用预检，免上下文重载）
     *
     * @param sourceState 当前源状态
     * @param event       触发事件
     * @return 若存在匹配路由且卫语句满足则返回 true，否则返回 false
     */
    default boolean canFire(S sourceState, E event) {
        return canFire(sourceState, event, (C) null);
    }

    /**
     * 实体状态只读预检门面（零侵入 Lambda 访问）
     *
     * @param entity  业务实体
     * @param getter  状态提取函数
     * @param event   触发事件
     * @param context 业务上下文
     * @param <T>     业务实体类型
     * @return 若可接受跃迁返回 true，否则返回 false
     */
    default <T> boolean canFire(T entity, Function<T, S> getter, E event, C context) {
        if (entity == null || getter == null) {
            return false;
        }
        return canFire(getter.apply(entity), event, context);
    }

    /**
     * 实体状态只读预检门面（免上下文重载）
     *
     * @param entity 业务实体
     * @param getter 状态提取函数
     * @param event  触发事件
     * @param <T>    业务实体类型
     * @return 若可接受跃迁返回 true，否则返回 false
     */
    default <T> boolean canFire(T entity, Function<T, S> getter, E event) {
        return canFire(entity, getter, event, (C) null);
    }

    /**
     * 试算状态跃迁结果（纯只读无副作用预检）
     * <p>仅试算目标状态与卫语句，100% 杜绝触发任何 Action 动作与持久层更新</p>
     *
     * @param sourceState 当前源状态
     * @param event       触发事件
     * @param context     业务上下文
     * @return 结构化预检结果（成功时携带目标状态，失败时携带失败原因及异常根因）
     */
    TransitionResult<S> verifyTransition(S sourceState, E event, C context);

    /**
     * 试算状态跃迁结果（纯只读无副作用预检，免上下文重载）
     *
     * @param sourceState 当前源状态
     * @param event       触发事件
     * @return 结构化预检结果
     */
    default TransitionResult<S> verifyTransition(S sourceState, E event) {
        return verifyTransition(sourceState, event, (C) null);
    }

    /**
     * 实体状态试算跃迁预检门面（零侵入 Lambda 访问）
     *
     * @param entity  业务实体
     * @param getter  状态提取函数
     * @param event   触发事件
     * @param context 业务上下文
     * @param <T>     业务实体类型
     * @return 结构化预检结果
     */
    default <T> TransitionResult<S> verifyTransition(T entity, Function<T, S> getter, E event, C context) {
        if (entity == null) {
            return TransitionResult.failure(null, event, "Target entity must not be null");
        }
        if (getter == null) {
            throw new IllegalArgumentException("Getter must not be null");
        }
        return verifyTransition(getter.apply(entity), event, context);
    }

    /**
     * 实体状态试算跃迁预检门面（免上下文重载）
     *
     * @param entity 业务实体
     * @param getter 状态提取函数
     * @param event  触发事件
     * @param <T>    业务实体类型
     * @return 结构化预检结果
     */
    default <T> TransitionResult<S> verifyTransition(T entity, Function<T, S> getter, E event) {
        return verifyTransition(entity, getter, event, (C) null);
    }

    /**
     * 获取当前状态下所有满足卫语句的可用事件集合（纯只读无副作用计算）
     *
     * @param sourceState 当前源状态
     * @param context     业务上下文
     * @return 可用事件集合（保底返回不可变空集合，严禁返回 null）
     */
    Set<E> getAvailableEvents(S sourceState, C context);

    /**
     * 获取当前状态下所有满足卫语句的可用事件集合（纯只读无副作用计算，免上下文重载）
     *
     * @param sourceState 当前源状态
     * @return 可用事件集合
     */
    default Set<E> getAvailableEvents(S sourceState) {
        return getAvailableEvents(sourceState, (C) null);
    }

    /**
     * 实体当前状态可用事件集合查询门面（零侵入 Lambda 访问）
     *
     * @param entity  业务实体
     * @param getter  状态提取函数
     * @param context 业务上下文
     * @param <T>     业务实体类型
     * @return 可用事件集合
     */
    default <T> Set<E> getAvailableEvents(T entity, Function<T, S> getter, C context) {
        if (entity == null || getter == null) {
            return java.util.Collections.emptySet();
        }
        return getAvailableEvents(getter.apply(entity), context);
    }

    /**
     * 实体当前状态可用事件集合查询门面（免上下文重载）
     *
     * @param entity 业务实体
     * @param getter 状态提取函数
     * @param <T>    业务实体类型
     * @return 可用事件集合
     */
    default <T> Set<E> getAvailableEvents(T entity, Function<T, S> getter) {
        return getAvailableEvents(entity, getter, (C) null);
    }

    /**
     * 安全探测触发状态跃迁（静默模式）
     * <p>静默执行状态跃迁（捕获异常并返回结果对象，包含后置动作副作用执行；若需纯只读无副作用预检，请使用 {@link #canFire} 或 {@link #verifyTransition}）</p>
     *
     * @param sourceState 当前源状态
     * @param event       触发事件
     * @param context     业务上下文
     * @return 结构化跃迁结果
     */
    TransitionResult<S> fireEventQuietly(S sourceState, E event, C context);

    /**
     * 实体状态安全探测流转门面（静默模式）
     * <p>自动通过 getter 提取当前状态并探测跃迁，接受跃迁后自动通过 setter 回写实体状态</p>
     *
     * @param entity  业务实体
     * @param getter  状态提取函数
     * @param setter  状态回写消费器
     * @param event   触发事件
     * @param context 业务上下文
     * @param <T>     业务实体类型
     * @return 结构化跃迁结果
     */
    default <T> TransitionResult<S> fireEventQuietly(T entity, Function<T, S> getter, BiConsumer<T, S> setter, E event, C context) {
        if (entity == null) {
            return TransitionResult.failure(null, event, "Target entity must not be null");
        }
        if (getter == null || setter == null) {
            throw new IllegalArgumentException("Getter and setter must not be null");
        }
        S currentState = getter.apply(entity);
        TransitionResult<S> result = fireEventQuietly(currentState, event, context);
        if (result.isAccepted()) {
            setter.accept(entity, result.getTargetState());
        }
        return result;
    }

    /**
     * 实体状态 CAS 乐观锁防重流转门面（Fail-Fast 模式）
     * <p>按序执行生命周期：试算目标状态与卫语句 -> 执行持久化 CAS 条件更新 -> 校验影响行数 -> 回写实体状态 -> 执行动作链</p>
     * <p>受影响行数为 0 时判定为并发冲突并阻断动作执行</p>
     *
     * @param entity     业务实体
     * @param getter     状态提取函数
     * @param setter     状态回写消费器
     * @param event      触发事件
     * @param context    业务上下文
     * @param casUpdater 数据库 CAS 乐观锁持久化更新函数
     * @param <T>        业务实体类型
     * @return 跃迁并成功持久化后的目标状态
     */
    default <T> S fireEventWithCas(T entity, Function<T, S> getter, BiConsumer<T, S> setter, E event, C context, CasUpdater<T, S> casUpdater) {
        throw new UnsupportedOperationException("CAS-based transition is not supported by this StateMachine implementation");
    }

    /**
     * 实体状态 CAS 乐观锁防重流转门面（免上下文重载）
     *
     * @param entity     业务实体
     * @param getter     状态提取函数
     * @param setter     状态回写消费器
     * @param event      触发事件
     * @param casUpdater 数据库 CAS 乐观锁持久化更新函数
     * @param <T>        业务实体类型
     * @return 跃迁并成功持久化后的目标状态
     */
    default <T> S fireEventWithCas(T entity, Function<T, S> getter, BiConsumer<T, S> setter, E event, CasUpdater<T, S> casUpdater) {
        return fireEventWithCas(entity, getter, setter, event, (C) null, casUpdater);
    }

    /**
     * 实体状态 CAS 乐观锁防重安全探测门面（静默模式）
     * <p>探测跃迁并执行持久化 CAS 条件更新，受影响行数为 0 时返回失败结果</p>
     *
     * @param entity     业务实体
     * @param getter     状态提取函数
     * @param setter     状态回写消费器
     * @param event      触发事件
     * @param context    业务上下文
     * @param casUpdater 数据库 CAS 乐观锁持久化更新函数
     * @param <T>        业务实体类型
     * @return 结构化跃迁结果
     */
    default <T> TransitionResult<S> fireEventWithCasQuietly(T entity, Function<T, S> getter, BiConsumer<T, S> setter, E event, C context, CasUpdater<T, S> casUpdater) {
        throw new UnsupportedOperationException("CAS-based transition is not supported by this StateMachine implementation");
    }

    /**
     * 实体状态 CAS 乐观锁防重安全探测门面（免上下文静默模式重载）
     *
     * @param entity     业务实体
     * @param getter     状态提取函数
     * @param setter     状态回写消费器
     * @param event      触发事件
     * @param casUpdater 数据库 CAS 乐观锁持久化更新函数
     * @param <T>        业务实体类型
     * @return 结构化跃迁结果
     */
    default <T> TransitionResult<S> fireEventWithCasQuietly(T entity, Function<T, S> getter, BiConsumer<T, S> setter, E event, CasUpdater<T, S> casUpdater) {
        return fireEventWithCasQuietly(entity, getter, setter, event, (C) null, casUpdater);
    }

    /**
     * 动态导出 Mermaid 状态图格式文本（stateDiagram-v2）
     *
     * @return Mermaid 状态机图例定义文本
     */
    String generateMermaid();

    /**
     * 导出 Mermaid 图例语法文本（generateMermaid 语义别名）
     *
     * @return Mermaid 状态机图例定义文本
     */
    default String exportMermaidDiagram() {
        return generateMermaid();
    }

    /**
     * 动态导出 PlantUML 状态图格式文本
     *
     * @return PlantUML 状态机图例定义文本
     */
    String generatePlantUML();

    /**
     * 注册切面拦截器
     *
     * @param interceptor 状态机拦截器
     */
    default void addInterceptor(StateMachineInterceptor<? super S, ? super E, ? super C> interceptor) {
    }

    /**
     * 注销切面拦截器
     *
     * @param interceptor 状态机拦截器
     * @return 若注销成功返回 true，否则返回 false
     */
    default boolean removeInterceptor(StateMachineInterceptor<? super S, ? super E, ? super C> interceptor) {
        return false;
    }

    /**
     * 获取已注册的切面拦截器只读视图
     *
     * @return 拦截器列表
     */
    default List<StateMachineInterceptor<? super S, ? super E, ? super C>> getInterceptors() {
        return Collections.emptyList();
    }

    /**
     * 注册状态跃迁轨迹审计监听器
     *
     * @param listener 审计监听器
     */
    default void addAuditListener(StateTransitionAuditListener<? super S, ? super E, ? super C> listener) {
    }

    /**
     * 注销状态跃迁轨迹审计监听器
     *
     * @param listener 审计监听器
     * @return 若注销成功返回 true，否则返回 false
     */
    default boolean removeAuditListener(StateTransitionAuditListener<? super S, ? super E, ? super C> listener) {
        return false;
    }

    /**
     * 获取已注册的状态跃迁轨迹审计监听器只读视图
     *
     * @return 审计监听器列表
     */
    default List<StateTransitionAuditListener<? super S, ? super E, ? super C>> getAuditListeners() {
        return Collections.emptyList();
    }
}
