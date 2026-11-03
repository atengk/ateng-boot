package io.github.atengk.boot.liteflow.metrics;

import com.yomahub.liteflow.core.NodeComponent;
import com.yomahub.liteflow.lifecycle.PostProcessChainExecuteLifeCycle;
import com.yomahub.liteflow.lifecycle.PostProcessNodeExecuteLifeCycle;
import com.yomahub.liteflow.slot.Slot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * LiteFlow 云原生 Micrometer / Prometheus 监控指标度量导出器
 * <p>
 * 深度挂载 LiteFlow 核心生命周期（{@link PostProcessChainExecuteLifeCycle} 与 {@link PostProcessNodeExecuteLifeCycle}），
 * 自动向 Micrometer 注册并暴露以下三维指标：
 * <ol>
 *   <li><b>规则链执行计数器 (Counter)</b>：{@code liteflow.chain.executions}，多维标签：{@code chainId}、{@code status} (SUCCESS/FAIL)；</li>
 *   <li><b>规则链执行耗时直方图 (Timer)</b>：{@code liteflow.chain.duration}，多维标签：{@code chainId}、{@code status}；</li>
 *   <li><b>单组件节点耗时直方图 (Timer)</b>：{@code liteflow.node.duration}，多维标签：{@code chainId}、{@code nodeId}、{@code status}。</li>
 * </ol>
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class LiteflowMetricsBinder implements MeterBinder, PostProcessChainExecuteLifeCycle, PostProcessNodeExecuteLifeCycle {

    private static final Logger log = LoggerFactory.getLogger(LiteflowMetricsBinder.class);

    public static final String METRIC_CHAIN_EXECUTIONS = "liteflow.chain.executions";
    public static final String METRIC_CHAIN_DURATION = "liteflow.chain.duration";
    public static final String METRIC_NODE_DURATION = "liteflow.node.duration";

    public static final String TAG_CHAIN_ID = "chainId";
    public static final String TAG_NODE_ID = "nodeId";
    public static final String TAG_STATUS = "status";

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAIL = "FAIL";

    private static final String ATTACHMENT_START_NANO_PREFIX = "ATENG_LITEFLOW_START_NANO_";

    private volatile MeterRegistry meterRegistry;
    private final ChainMetricsLifeCycle chainLifeCycle;
    private final NodeMetricsLifeCycle nodeLifeCycle;

    public LiteflowMetricsBinder() {
        this(null);
    }

    public LiteflowMetricsBinder(MeterRegistry meterRegistry) {
        this.chainLifeCycle = new ChainMetricsLifeCycle(this);
        this.nodeLifeCycle = new NodeMetricsLifeCycle(this);
        if (meterRegistry != null) {
            bindTo(meterRegistry);
        }
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        if (registry == null) {
            return;
        }
        this.meterRegistry = registry;
        log.info("ateng-boot-liteflow: 成功绑定 Micrometer 监控指标导出器 [registry={}]", registry.getClass().getSimpleName());
    }

    public MeterRegistry getMeterRegistry() {
        return this.meterRegistry;
    }

    public ChainMetricsLifeCycle getChainLifeCycle() {
        return chainLifeCycle;
    }

    public NodeMetricsLifeCycle getNodeLifeCycle() {
        return nodeLifeCycle;
    }

    @Override
    public void postProcessBeforeChainExecute(String chainId, Slot slot) {
        this.chainLifeCycle.postProcessBeforeChainExecute(chainId, slot);
    }

    @Override
    public void postProcessAfterChainExecute(String chainId, Slot slot) {
        this.chainLifeCycle.postProcessAfterChainExecute(chainId, slot);
    }

    @Override
    public void postProcessBeforeNodeExecute(NodeComponent nodeComponent) {
        this.nodeLifeCycle.postProcessBeforeNodeExecute(nodeComponent);
    }

    @Override
    public void postProcessAfterNodeExecute(NodeComponent nodeComponent, long timeSpent, Exception exception) {
        this.nodeLifeCycle.postProcessAfterNodeExecute(nodeComponent, timeSpent, exception);
    }

    /**
     * 手动/编程式记录规则链执行度量
     *
     * @param chainId       规则链 ID
     * @param status        执行状态 (SUCCESS/FAIL)
     * @param durationNanos 耗时（纳秒）
     */
    public void recordChainExecution(String chainId, String status, long durationNanos) {
        if (this.meterRegistry == null) {
            return;
        }
        String safeChainId = (chainId != null && !chainId.isBlank()) ? chainId : "UNKNOWN";
        String safeStatus = (status != null && !status.isBlank()) ? status : STATUS_SUCCESS;

        try {
            Counter.builder(METRIC_CHAIN_EXECUTIONS)
                    .description("Total number of LiteFlow rule chain executions")
                    .tag(TAG_CHAIN_ID, safeChainId)
                    .tag(TAG_STATUS, safeStatus)
                    .register(this.meterRegistry)
                    .increment();

            if (durationNanos >= 0) {
                Timer.builder(METRIC_CHAIN_DURATION)
                        .description("Execution duration of LiteFlow rule chains")
                        .tag(TAG_CHAIN_ID, safeChainId)
                        .tag(TAG_STATUS, safeStatus)
                        .register(this.meterRegistry)
                        .record(durationNanos, TimeUnit.NANOSECONDS);
            }
        } catch (Throwable t) {
            log.warn("ateng-boot-liteflow: 采集规则链度量异常 [chainId={}, status={}]: {}",
                    safeChainId, safeStatus, t.getMessage());
        }
    }

    /**
     * 手动/编程式记录节点步骤执行度量
     *
     * @param chainId        规则链 ID
     * @param nodeId         节点 ID
     * @param status         执行状态 (SUCCESS/FAIL)
     * @param durationMillis 耗时（毫秒）
     */
    public void recordNodeExecution(String chainId, String nodeId, String status, long durationMillis) {
        if (this.meterRegistry == null) {
            return;
        }
        String safeChainId = (chainId != null && !chainId.isBlank()) ? chainId : "UNKNOWN";
        String safeNodeId = (nodeId != null && !nodeId.isBlank()) ? nodeId : "UNKNOWN";
        String safeStatus = (status != null && !status.isBlank()) ? status : STATUS_SUCCESS;

        try {
            if (durationMillis >= 0) {
                Timer.builder(METRIC_NODE_DURATION)
                        .description("Execution duration of LiteFlow node components")
                        .tag(TAG_CHAIN_ID, safeChainId)
                        .tag(TAG_NODE_ID, safeNodeId)
                        .tag(TAG_STATUS, safeStatus)
                        .register(this.meterRegistry)
                        .record(durationMillis, TimeUnit.MILLISECONDS);
            }
        } catch (Throwable t) {
            log.warn("ateng-boot-liteflow: 采集组件节点度量异常 [chainId={}, nodeId={}, status={}]: {}",
                    safeChainId, safeNodeId, safeStatus, t.getMessage());
        }
    }

    /**
     * 规则链执行生命周期监听专职适配器
     */
    public static class ChainMetricsLifeCycle implements PostProcessChainExecuteLifeCycle {
        private final LiteflowMetricsBinder binder;

        public ChainMetricsLifeCycle(LiteflowMetricsBinder binder) {
            this.binder = binder;
        }

        @Override
        public void postProcessBeforeChainExecute(String chainId, Slot slot) {
            if (slot != null && chainId != null) {
                slot.setAttachment(ATTACHMENT_START_NANO_PREFIX + chainId, System.nanoTime());
            }
        }

        @Override
        public void postProcessAfterChainExecute(String chainId, Slot slot) {
            long durationNanos = 0L;
            if (slot != null && chainId != null) {
                String key = ATTACHMENT_START_NANO_PREFIX + chainId;
                Long startNano = slot.getAttachment(key);
                if (startNano != null) {
                    durationNanos = System.nanoTime() - startNano;
                    slot.removeAttachment(key);
                }
            }
            String status = (slot != null && slot.getException() != null) ? STATUS_FAIL : STATUS_SUCCESS;
            binder.recordChainExecution(chainId, status, durationNanos);
        }
    }

    /**
     * 组件节点执行生命周期监听专职适配器
     */
    public static class NodeMetricsLifeCycle implements PostProcessNodeExecuteLifeCycle {
        private final LiteflowMetricsBinder binder;

        public NodeMetricsLifeCycle(LiteflowMetricsBinder binder) {
            this.binder = binder;
        }

        @Override
        public void postProcessBeforeNodeExecute(NodeComponent nodeComponent) {
        }

        @Override
        public void postProcessAfterNodeExecute(NodeComponent nodeComponent, long timeSpent, Exception exception) {
            if (nodeComponent == null) {
                return;
            }
            String chainId = nodeComponent.getCurrChainId();
            if (chainId == null || chainId.isBlank()) {
                chainId = nodeComponent.getChainId();
            }
            String nodeId = nodeComponent.getNodeId();
            String status = (exception == null) ? STATUS_SUCCESS : STATUS_FAIL;
            binder.recordNodeExecution(chainId, nodeId, status, timeSpent);
        }
    }
}
