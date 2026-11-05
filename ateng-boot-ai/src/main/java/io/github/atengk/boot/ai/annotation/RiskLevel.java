package io.github.atengk.boot.ai.annotation;

/**
 * 敏感高危工具操作风险等级枚举
 *
 * @author Ateng
 * @since 2026-11-06
 */
public enum RiskLevel {

    /**
     * 低风险（记录审计日志，默认不强制阻断）
     */
    LOW,

    /**
     * 中风险（需二次校验或确认）
     */
    MEDIUM,

    /**
     * 高风险（涉及财务、用户核心数据、批量操作，强制人机协同审批拦截）
     */
    HIGH,

    /**
     * 极高危/灾难级（涉及系统删除、全量重置，强制二人复核或多级审批）
     */
    CRITICAL
}
