package io.github.atengk.boot.liteflow.trace;

import java.io.Serializable;

/**
 * 带有结构化链路轨迹的 LiteFlow 执行结果包装
 *
 * @param <T>     业务上下文强类型泛型
 * @param context 强类型上下文实例
 * @param trace   结构化时序执行轨迹
 * @author Ateng
 * @since 2026-11-03
 */
public record LiteflowTraceResult<T>(T context, LiteflowExecutionTrace trace) implements Serializable {

    /**
     * 获取业务上下文强类型实例（JavaBean 兼容方法）
     *
     * @return 业务上下文实例
     */
    public T getContext() {
        return context;
    }

    /**
     * 获取结构化时序执行链路轨迹（JavaBean 兼容方法）
     *
     * @return 结构化链路轨迹
     */
    public LiteflowExecutionTrace getTrace() {
        return trace;
    }
}
