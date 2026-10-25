package io.github.atengk.boot.datasource.enums;

/**
 * 全库多租户批处理容错策略枚举
 *
 * @author Ateng
 * @since 2026-10-24
 */
public enum BatchExecutionPolicy {

    /**
     * 容错聚合模式（默认）：单个租户或数据源执行异常时不阻断循环，隔离异常并继续遍历其余租户，最后统一汇总返回
     */
    FAIL_SAFE,

    /**
     * 快速失败模式：遇到任何一个租户或数据源执行异常，立即抛出异常中断批处理循环
     */
    FAIL_FAST
}
