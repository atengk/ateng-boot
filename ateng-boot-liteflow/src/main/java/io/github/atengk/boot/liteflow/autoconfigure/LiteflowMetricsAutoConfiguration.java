package io.github.atengk.boot.liteflow.autoconfigure;

import com.yomahub.liteflow.core.FlowExecutor;
import com.yomahub.liteflow.lifecycle.LifeCycleHolder;
import io.github.atengk.boot.liteflow.metrics.LiteflowMetricsBinder;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * LiteFlow 云原生 Micrometer 性能指标度量监控自动装配
 * 当类路径存在 MeterRegistry 且配置启用时自动注册 {@link LiteflowMetricsBinder}
 *
 * @author Ateng
 * @since 2026-11-03
 */
@AutoConfiguration
@AutoConfigureAfter(
        value = {AtengLiteflowAutoConfiguration.class},
        name = {
                "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration",
                "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration"
        }
)
@ConditionalOnClass({FlowExecutor.class, MeterRegistry.class})
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnProperty(prefix = "ateng.boot.liteflow.metrics", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LiteflowMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LiteflowMetricsBinder liteflowMetricsBinder(MeterRegistry meterRegistry) {
        LiteflowMetricsBinder binder = new LiteflowMetricsBinder(meterRegistry);

        // 分别向 LifeCycleHolder 注册链级与节点级独立生命周期适配器，
        // 绕过 LiteFlow 内部单类只识别首个匹配生命周期接口的 if-else 判定限制
        if (LifeCycleHolder.getPostProcessChainExecuteLifeCycleList().stream()
                .noneMatch(lc -> lc == binder.getChainLifeCycle())) {
            LifeCycleHolder.addLifeCycle(binder.getChainLifeCycle());
        }
        if (LifeCycleHolder.getPostProcessNodeExecuteLifeCycleList().stream()
                .noneMatch(lc -> lc == binder.getNodeLifeCycle())) {
            LifeCycleHolder.addLifeCycle(binder.getNodeLifeCycle());
        }

        return binder;
    }
}
