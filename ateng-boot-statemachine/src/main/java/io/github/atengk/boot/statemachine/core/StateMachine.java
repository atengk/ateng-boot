package io.github.atengk.boot.statemachine.core;

import io.github.atengk.boot.statemachine.model.TransitionResult;

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
     * 安全探测触发状态跃迁（静默模式）
     * <p>不向外抛出异常，返回结构化结果承载对象，供前端按钮权限计算或状态预检使用</p>
     *
     * @param sourceState 当前源状态
     * @param event       触发事件
     * @param context     业务上下文
     * @return 结构化跃迁结果
     */
    TransitionResult<S> fireEventQuietly(S sourceState, E event, C context);

    /**
     * 动态导出 Mermaid 状态图格式文本（stateDiagram-v2）
     *
     * @return Mermaid 状态机图例定义文本
     */
    String generateMermaid();

    /**
     * 动态导出 PlantUML 状态图格式文本
     *
     * @return PlantUML 状态机图例定义文本
     */
    String generatePlantUML();
}
