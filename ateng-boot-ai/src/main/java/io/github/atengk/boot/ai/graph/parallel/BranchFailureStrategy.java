package io.github.atengk.boot.ai.graph.parallel;

/**
 * 并行扇出认知分支失败容错策略枚举
 *
 * @author Ateng
 * @since 2026-11-22
 */
public enum BranchFailureStrategy {

    /**
     * 快速失败熔断：任一分支抛出异常或超时立即中断取消其余未完成分支，并向上抛出语义化 {@code AiException}（保留原始 {@code cause} 因果链）
     */
    FAIL_FAST,

    /**
     * 尽力汇聚降级：单个分支失败或超时时将结构化错误信息记录至 {@code branchErrors} 状态槽，不中断其余健康分支，全部就绪后交由 {@code aggregate} 汇聚裁决
     */
    BEST_EFFORT
}
