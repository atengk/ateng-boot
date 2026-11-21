package io.github.atengk.boot.liteflow.core;

/**
 * 慢规则与慢节点执行可观测指标回调接口
 * <p>
 * 用于在 {@link LiteflowTemplate} 与可选的 Micrometer 度量层之间建立零强依赖解耦桥梁，
 * 避免在未引入 {@code micrometer-core} 的应用类路径上加载可选度量类引发 {@code NoClassDefFoundError}。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
public interface LiteflowSlowExecutionRecorder {

    /**
     * 记录慢规则链执行
     *
     * @param chainId 规则链标识
     */
    void recordSlowChain(String chainId);

    /**
     * 记录慢组件节点执行
     *
     * @param chainId 规则链标识
     * @param nodeId  组件节点标识
     */
    void recordSlowNode(String chainId, String nodeId);
}
