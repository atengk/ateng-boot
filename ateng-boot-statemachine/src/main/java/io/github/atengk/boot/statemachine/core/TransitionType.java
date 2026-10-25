package io.github.atengk.boot.statemachine.core;

/**
 * 状态机跃迁拓扑类型枚举
 *
 * @author Ateng
 * @since 2026-10-25
 */
public enum TransitionType {

    /**
     * 外部跃迁 (S1 -> S2)
     */
    EXTERNAL,

    /**
     * 内部自跃迁 (S1 -> S1)
     */
    INTERNAL,

    /**
     * 条件分支路由跃迁 (Choice)
     */
    CHOICE
}
