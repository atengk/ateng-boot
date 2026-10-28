package io.github.atengk.boot.statemachine.builder;

import io.github.atengk.boot.statemachine.core.Action;
import io.github.atengk.boot.statemachine.core.Condition;

import java.util.function.Consumer;

/**
 * 外部跃迁流式构建器契约阶段定义
 *
 * @param <S> 业务状态类型
 * @param <E> 触发事件类型
 * @param <C> 业务上下文类型
 * @author Ateng
 * @since 2026-10-25
 */
public interface ExternalTransitionBuilder<S, E, C> {

    interface From<S, E, C> {
        /**
         * 指定源状态
         *
         * @param sourceState 源状态
         * @return 下一步: 指定目标状态
         */
        To<S, E, C> from(S sourceState);
    }

    interface To<S, E, C> {
        /**
         * 指定目标状态
         *
         * @param targetState 目标状态
         * @return 下一步: 指定触发事件
         */
        On<S, E, C> to(S targetState);
    }

    interface On<S, E, C> {
        /**
         * 指定触发事件
         *
         * @param event 触发事件
         * @return 下一步: 配置前置条件或动作
         */
        WhenOrPerform<S, E, C> on(E event);
    }

    interface WhenOrPerform<S, E, C> {
        /**
         * 添加前置卫语句条件（支持链式添加多个，逻辑为 AND）
         *
         * @param condition 卫语句
         * @return 当前构建阶段
         */
        WhenOrPerform<S, E, C> when(Condition<C> condition);

        /**
         * 添加跃迁执行动作（支持链式添加多个，按声明顺序同步执行）
         *
         * @param action 执行动作
         * @return 当前构建阶段
         */
        WhenOrPerform<S, E, C> perform(Action<S, E, C> action);

        /**
         * 便捷添加仅消费上下文的动作
         *
         * @param consumer 上下文消费函数
         * @return 当前构建阶段
         */
        default WhenOrPerform<S, E, C> perform(Consumer<C> consumer) {
            if (consumer == null) {
                throw new IllegalArgumentException("Action consumer must not be null");
            }
            return perform((from, to, event, context) -> consumer.accept(context));
        }
    }
}
