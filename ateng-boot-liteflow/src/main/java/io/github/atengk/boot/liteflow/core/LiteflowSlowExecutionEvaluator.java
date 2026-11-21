package io.github.atengk.boot.liteflow.core;

import io.github.atengk.boot.liteflow.properties.AtengLiteflowProperties;
import io.github.atengk.boot.liteflow.trace.ExecutionStepItem;
import io.github.atengk.boot.liteflow.trace.LiteflowExecutionTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 慢规则链与慢组件节点 Duration 阈值评估及聚合轨迹治理协作者
 *
 * @author Ateng
 * @since 2026-11-21
 */
final class LiteflowSlowExecutionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(LiteflowSlowExecutionEvaluator.class);

    private final AtengLiteflowProperties properties;
    private final LiteflowSlowExecutionRecorder slowExecutionRecorder;

    LiteflowSlowExecutionEvaluator(AtengLiteflowProperties properties,
                                   LiteflowSlowExecutionRecorder slowExecutionRecorder) {
        this.properties = properties;
        this.slowExecutionRecorder = slowExecutionRecorder;
    }

    void evaluateAndRecord(String chainId, LiteflowExecutionTrace trace) {
        if (trace == null || this.properties == null || this.properties.getTrace() == null) {
            return;
        }
        AtengLiteflowProperties.TraceProperties traceProps = this.properties.getTrace();
        Duration chainThreshold = traceProps.getSlowChainThreshold();
        Duration nodeThreshold = traceProps.getSlowNodeThreshold();

        boolean chainSlow = isPositiveDuration(chainThreshold)
                && trace.getTotalTimeSpentMs() >= chainThreshold.toMillis();

        Set<String> slowNodes = new LinkedHashSet<>();
        List<String> slowNodeExecutions = Collections.emptyList();
        if (isPositiveDuration(nodeThreshold) && trace.getSteps() != null && !trace.getSteps().isEmpty()) {
            long nodeThresholdMs = nodeThreshold.toMillis();
            List<String> recorded = null;
            for (ExecutionStepItem step : trace.getSteps()) {
                if (step != null && step.getNodeId() != null && step.getTimeSpentMs() >= nodeThresholdMs) {
                    slowNodes.add(step.getNodeId());
                    if (recorded == null) {
                        recorded = new ArrayList<>();
                    }
                    recorded.add(step.getNodeId());
                }
            }
            if (recorded != null) {
                slowNodeExecutions = recorded;
            }
        }

        trace.setSlowNodeIds(slowNodes.isEmpty() ? Collections.emptyList() : new ArrayList<>(slowNodes));
        trace.setSlow(chainSlow || !slowNodes.isEmpty());

        if (!trace.isSlow()) {
            return;
        }

        log.warn("ateng-boot-liteflow: 检测到慢规则执行 [chainId={}, totalTimeMs={}, slowChainThresholdMs={}, slowNodeIds={}, slowNodeThresholdMs={}]",
                chainId,
                trace.getTotalTimeSpentMs(),
                chainThreshold != null ? chainThreshold.toMillis() : null,
                trace.getSlowNodeIds(),
                nodeThreshold != null ? nodeThreshold.toMillis() : null);

        if (this.slowExecutionRecorder != null) {
            try {
                if (chainSlow) {
                    this.slowExecutionRecorder.recordSlowChain(chainId);
                }
                for (String slowNodeId : slowNodeExecutions) {
                    this.slowExecutionRecorder.recordSlowNode(chainId, slowNodeId);
                }
            } catch (Throwable t) {
                log.warn("ateng-boot-liteflow: 上报慢执行度量指标异常已忽略 [chainId={}]: {}", chainId, t.getMessage());
            }
        }
    }

    LiteflowExecutionTrace buildRouteAggregateTrace(String routeChainId,
                                                    List<LiteflowExecutionTrace> traces,
                                                    long totalDuration) {
        if (traces.size() == 1) {
            return traces.get(0);
        }
        LiteflowExecutionTrace aggregate = new LiteflowExecutionTrace();
        aggregate.setChainId(routeChainId);
        aggregate.setSuccess(true);
        long clampedDuration = Math.max(totalDuration, 0L);
        aggregate.setTotalTimeSpentMs(clampedDuration);

        Set<String> aggregatedSlowNodes = new LinkedHashSet<>();
        boolean anySubSlow = false;
        for (LiteflowExecutionTrace item : traces) {
            if (item == null) {
                continue;
            }
            if (item.getSteps() != null) {
                aggregate.getSteps().addAll(item.getSteps());
            }
            if (item.isSlow()) {
                anySubSlow = true;
            }
            if (item.getSlowNodeIds() != null && !item.getSlowNodeIds().isEmpty()) {
                aggregatedSlowNodes.addAll(item.getSlowNodeIds());
            }
        }

        Duration chainThreshold = (this.properties != null && this.properties.getTrace() != null)
                ? this.properties.getTrace().getSlowChainThreshold()
                : null;
        boolean routeSlow = isPositiveDuration(chainThreshold) && clampedDuration >= chainThreshold.toMillis();
        aggregate.setSlowNodeIds(aggregatedSlowNodes.isEmpty()
                ? Collections.emptyList()
                : new ArrayList<>(aggregatedSlowNodes));
        aggregate.setSlow(anySubSlow || routeSlow || !aggregatedSlowNodes.isEmpty());
        return aggregate;
    }

    private static boolean isPositiveDuration(Duration duration) {
        return duration != null && !duration.isNegative() && !duration.isZero();
    }
}
