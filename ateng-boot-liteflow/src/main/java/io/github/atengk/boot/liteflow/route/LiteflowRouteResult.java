package io.github.atengk.boot.liteflow.route;

import io.github.atengk.boot.liteflow.trace.LiteflowExecutionTrace;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * LiteFlow 决策路由链聚合执行结果包装
 * 封装多规则路由匹配后的共享强类型上下文、全部命中的流程链标识列表、各链路结构化执行轨迹与端到端耗时
 *
 * @param <T> 业务上下文类型
 * @author Ateng
 * @since 2026-11-06
 */
public record LiteflowRouteResult<T>(
        T context,
        List<String> matchedChainIds,
        List<LiteflowExecutionTrace> traces,
        long totalDurationMs
) implements Serializable {

    public LiteflowRouteResult {
        matchedChainIds = matchedChainIds != null ? Collections.unmodifiableList(matchedChainIds) : Collections.emptyList();
        traces = traces != null ? Collections.unmodifiableList(traces) : Collections.emptyList();
    }

    /**
     * 获取聚合更新后的强类型业务上下文
     */
    public T getContext() {
        return context;
    }

    /**
     * 获取所有匹配命中的流程链 ID 列表
     */
    public List<String> getMatchedChainIds() {
        return matchedChainIds;
    }

    /**
     * 获取所有匹配流程链的结构化时序执行轨迹列表
     */
    public List<LiteflowExecutionTrace> getTraces() {
        return traces;
    }

    /**
     * 获取决策路由执行总耗时（毫秒）
     */
    public long getTotalDurationMs() {
        return totalDurationMs;
    }

    /**
     * 判断是否有任意规则链匹配命中
     *
     * @return true 表示至少命中一条规则链
     */
    public boolean hasMatched() {
        return !matchedChainIds.isEmpty();
    }

    /**
     * 获取匹配命中的规则链总数
     *
     * @return 命中数量
     */
    public int getMatchedCount() {
        return matchedChainIds.size();
    }
}
