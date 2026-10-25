package io.github.atengk.boot.statemachine.builder;

import io.github.atengk.boot.statemachine.core.Action;
import io.github.atengk.boot.statemachine.core.Condition;

import java.util.function.Consumer;

/**
 * Choice 动态条件分支路由跃迁流式构建器契约阶段定义
 *
 * @param <S> 业务状态类型
 * @param <E> 触发事件类型
 * @param <C> 业务上下文类型
 * @author Ateng
 * @since 2026-10-25
 */
public interface ChoiceTransitionBuilder<S, E, C> {

    interface From<S, E, C> {
        /**
         * 指定源状态
         *
         * @param sourceState 源状态
         * @return 下一步: 指定触发事件
         */
        On<S, E, C> from(S sourceState);
    }

    interface On<S, E, C> {
        /**
         * 指定触发事件
         *
         * @param event 触发事件
         * @return 下一步: 指定首个分支条件
         */
        BranchWhen<S, E, C> on(E event);
    }

    interface BranchWhen<S, E, C> {
        /**
         * 声明条件分支卫语句
         *
         * @param condition 卫语句条件
         * @return 下一步: 指定该分支的目标状态
         */
        BranchTo<S, E, C> when(Condition<C> condition);
    }

    interface BranchTo<S, E, C> {
        /**
         * 添加额外的卫语句条件（与当前分支既有条件为 AND 关系）
         *
         * @param condition 额外卫语句条件
         * @return 当前阶段
         */
        BranchTo<S, E, C> when(Condition<C> condition);

        /**
         * 指定当前分支匹配成功时的目标状态
         *
         * @param targetState 目标状态
         * @return 下一步: 链式追加新分支或指定兜底默认状态
         */
        BranchChoice<S, E, C> to(S targetState);
    }

    interface BranchChoice<S, E, C> {
        /**
         * 链式追加下一个条件分支
         *
         * @param condition 卫语句条件
         * @return 下一步: 指定新分支的目标状态
         */
        BranchTo<S, E, C> when(Condition<C> condition);

        /**
         * 指定所有条件分支均不满足时的兜底目标状态
         *
         * @param defaultTargetState 兜底默认目标状态
         * @return 完成或可选挂载动作
         */
        PerformOrEnd<S, E, C> defaultTo(S defaultTargetState);
    }

    interface PerformOrEnd<S, E, C> {
        /**
         * 添加跃迁执行动作（在 Choice 动态裁决出具体目标状态后执行）
         *
         * @param action 执行动作
         * @return 当前阶段
         */
        PerformOrEnd<S, E, C> perform(Action<S, E, C> action);

        /**
         * 便捷添加仅消费上下文的动作
         *
         * @param consumer 上下文消费函数
         * @return 当前阶段
         */
        default PerformOrEnd<S, E, C> perform(Consumer<C> consumer) {
            if (consumer == null) {
                throw new IllegalArgumentException("Action consumer must not be null");
            }
            return perform((from, to, event, context) -> consumer.accept(context));
        }
    }
}
