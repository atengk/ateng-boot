package io.github.atengk.boot.statemachine.enums;

/**
 * 状态机终态幂等放行策略
 * <p>用于控制当实体当前状态已处于跃迁目标状态且重复收到同一事件时的治理行为</p>
 *
 * @author Ateng
 * @since 2026-11-01
 */
public enum IdempotentStrategy {

    /**
     * 禁用幂等放行（默认策略，遇到重复事件抛出 TRANSITION_NOT_FOUND 异常）
     */
    NONE,

    /**
     * 静默放行并接受（当实体已处于目标状态时，判定为幂等成功放行，跳过 Action 与数据库更新）
     */
    ACCEPT
}
