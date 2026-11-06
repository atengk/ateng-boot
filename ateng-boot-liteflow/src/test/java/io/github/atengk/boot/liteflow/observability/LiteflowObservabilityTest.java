package io.github.atengk.boot.liteflow.observability;

import com.yomahub.liteflow.core.NodeComponent;
import com.yomahub.liteflow.flow.LiteflowResponse;
import io.github.atengk.boot.liteflow.component.BaseNodeComponent;
import io.github.atengk.boot.liteflow.core.LiteflowTemplate;
import io.github.atengk.boot.liteflow.exception.LiteflowExecutionException;
import io.github.atengk.boot.liteflow.metrics.LiteflowMetricsBinder;
import io.github.atengk.boot.liteflow.trace.ExecutionStepItem;
import io.github.atengk.boot.liteflow.trace.ExecutionStepStatus;
import io.github.atengk.boot.liteflow.trace.LiteflowExecutionTrace;
import io.github.atengk.boot.liteflow.trace.LiteflowTraceResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.Data;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LiteFlow 结构化时序执行轨迹与 Micrometer 云原生可观测性能度量全景单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
@SpringBootTest(classes = LiteflowObservabilityTest.TestConfig.class, properties = {
        "liteflow.rule-source=classpath:liteflow/observability-flow.el.xml",
        "liteflow.enable=true",
        "ateng.boot.liteflow.trace.enable-mdc=true",
        "ateng.boot.liteflow.trace.mdc-key=liteflow_trace",
        "ateng.boot.liteflow.metrics.enabled=true"
})
@DisplayName("LiteFlow 结构化时序轨迹与 Micrometer 云原生度量测试")
class LiteflowObservabilityTest {

    @Autowired
    private LiteflowTemplate liteflowTemplate;

    @Autowired
    private LiteflowMetricsBinder liteflowMetricsBinder;

    @Autowired
    private SimpleMeterRegistry meterRegistry;

    @AfterEach
    void tearDown() {
        MDC.clear();
        LiteflowExecutionTrace.clearFromMdc();
    }

    @Test
    @DisplayName("验证正常执行链路的结构化时序轨迹捕获完整性")
    void testExecuteWithTraceSuccess() {
        MDC.put("traceId", "TRACE-SUCCESS-001");

        LiteflowTraceResult<ObsContext> result = liteflowTemplate.executeWithTrace(
                "normalTraceChain", "testParam", ObsContext.class);

        assertThat(result).isNotNull();
        assertThat(result.getContext()).isNotNull();
        assertThat(result.getContext().getExecutedNodes()).containsExactly("obsNodeA", "obsNodeB");

        LiteflowExecutionTrace trace = result.getTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.getChainId()).isEqualTo("normalTraceChain");
        assertThat(trace.isSuccess()).isTrue();
        assertThat(trace.getTraceId()).isEqualTo("TRACE-SUCCESS-001");
        assertThat(trace.getTotalTimeSpentMs()).isGreaterThanOrEqualTo(0L);
        assertThat(trace.hasFailedStep()).isFalse();
        assertThat(trace.getFailedStep()).isNull();

        // 验证步骤时序与明细
        List<ExecutionStepItem> steps = trace.getSteps();
        assertThat(steps).hasSize(2);

        ExecutionStepItem step0 = steps.get(0);
        assertThat(step0.getNodeId()).isEqualTo("obsNodeA");
        assertThat(step0.getStatus()).isEqualTo(ExecutionStepStatus.SUCCESS);
        assertThat(step0.getTimeSpentMs()).isGreaterThanOrEqualTo(0L);

        ExecutionStepItem step1 = steps.get(1);
        assertThat(step1.getNodeId()).isEqualTo("obsNodeB");
        assertThat(step1.getStatus()).isEqualTo(ExecutionStepStatus.SUCCESS);
        assertThat(step1.getTimeSpentMs()).isGreaterThanOrEqualTo(0L);

        // 验证辅助查找方法
        assertThat(trace.getStep("obsNodeA")).isSameAs(step0);
        assertThat(trace.getSteps("obsNodeB")).containsExactly(step1);

        // 验证摘要与 JSON 序列化
        String summary = trace.formatSummary();
        assertThat(summary)
                .contains("chainId=normalTraceChain")
                .contains("success=true")
                .contains("obsNodeA")
                .contains("obsNodeB");

        String json = trace.toJson();
        assertThat(json).isNotBlank().contains("\"chainId\":\"normalTraceChain\"");
    }

    @Test
    @DisplayName("验证带有 Tag 标签的组件时序轨迹捕获与格式化")
    void testExecuteWithTraceTagged() {
        LiteflowTraceResult<ObsContext> result = liteflowTemplate.executeWithTrace(
                "taggedTraceChain", ObsContext.class);

        LiteflowExecutionTrace trace = result.getTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.isSuccess()).isTrue();
        assertThat(trace.getSteps()).hasSize(2);

        ExecutionStepItem step0 = trace.getSteps().get(0);
        assertThat(step0.getNodeId()).isEqualTo("obsNodeA");
        assertThat(step0.getTag()).isEqualTo("VIP");

        ExecutionStepItem step1 = trace.getSteps().get(1);
        assertThat(step1.getNodeId()).isEqualTo("obsNodeB");
        assertThat(step1.getTag()).isEqualTo("EXPRESS");

        String summary = trace.formatSummary();
        assertThat(summary)
                .contains("obsNodeA(VIP)")
                .contains("obsNodeB(EXPRESS)");
    }

    @Test
    @DisplayName("验证失败规则链时序轨迹捕获、失败节点定位与异常摘要透传")
    void testExecuteWithTraceFailure() {
        MDC.put("traceId", "TRACE-FAIL-999");

        assertThatThrownBy(() -> liteflowTemplate.executeWithTrace("failTraceChain", ObsContext.class))
                .isInstanceOf(LiteflowExecutionException.class)
                .satisfies(ex -> {
                    LiteflowExecutionException lex = (LiteflowExecutionException) ex;
                    assertThat(lex.getChainId()).isEqualTo("failTraceChain");
                    assertThat(lex.getFailedNodeId()).isEqualTo("obsFailNode");

                    LiteflowExecutionTrace trace = lex.getTrace();
                    assertThat(trace).isNotNull();
                    assertThat(trace.isSuccess()).isFalse();
                    assertThat(trace.hasFailedStep()).isTrue();
                    assertThat(trace.getTraceId()).isEqualTo("TRACE-FAIL-999");

                    ExecutionStepItem failedStep = trace.getFailedStep();
                    assertThat(failedStep).isNotNull();
                    assertThat(failedStep.getNodeId()).isEqualTo("obsFailNode");
                    assertThat(failedStep.getStatus()).isEqualTo(ExecutionStepStatus.FAIL);
                    assertThat(failedStep.getErrorSummary()).contains("模拟组件异常失败");

                    String summary = trace.formatSummary();
                    assertThat(summary)
                            .contains("chainId=failTraceChain")
                            .contains("success=false")
                            .contains("obsFailNode")
                            .contains("FAIL:模拟组件异常失败");
                });
    }

    @Test
    @DisplayName("验证 Saga 反向回滚场景的 ROLLBACK 步骤状态正确标记")
    void testExecuteWithTraceRollback() {
        assertThatThrownBy(() -> liteflowTemplate.executeWithTrace("rollbackTraceChain", ObsContext.class))
                .isInstanceOf(LiteflowExecutionException.class)
                .satisfies(ex -> {
                    LiteflowExecutionException lex = (LiteflowExecutionException) ex;
                    LiteflowExecutionTrace trace = lex.getTrace();
                    assertThat(trace).isNotNull();
                    assertThat(trace.isSuccess()).isFalse();

                    ExecutionStepItem rollbackStep = trace.getStep("obsRollbackNodeA");
                    assertThat(rollbackStep).isNotNull();
                    assertThat(rollbackStep.getStatus()).isEqualTo(ExecutionStepStatus.ROLLBACK);

                    ExecutionStepItem failStep = trace.getStep("obsFailNode");
                    assertThat(failStep).isNotNull();
                    assertThat(failStep.getStatus()).isEqualTo(ExecutionStepStatus.FAIL);
                });
    }

    @Test
    @DisplayName("验证 MDC 链路轨迹自动注入与手动提取注入清理")
    void testMdcInjectionAndCleanup() {
        MDC.put("traceId", "TRACE-MDC-888");

        // 执行成功链路，由于配置了 ateng.boot.liteflow.trace.enable-mdc=true，自动注入 liteflow_trace
        liteflowTemplate.executeWithTrace("normalTraceChain", ObsContext.class);

        String mdcTrace = MDC.get("liteflow_trace");
        assertThat(mdcTrace).isNotNull();
        assertThat(mdcTrace)
                .contains("chainId=normalTraceChain")
                .contains("success=true")
                .contains("traceId=TRACE-MDC-888")
                .contains("obsNodeA")
                .contains("obsNodeB");

        // 清理验证
        LiteflowExecutionTrace.clearFromMdc("liteflow_trace");
        assertThat(MDC.get("liteflow_trace")).isNull();

        // 门面清理验证
        liteflowTemplate.executeWithTrace("normalTraceChain", ObsContext.class);
        assertThat(MDC.get("liteflow_trace")).isNotNull();
        liteflowTemplate.clearTraceMdc();
        assertThat(MDC.get("liteflow_trace")).isNull();

        // 作用域自动清理验证
        LiteflowTraceResult<ObsContext> result = liteflowTemplate.executeWithTrace("normalTraceChain", ObsContext.class);
        liteflowTemplate.clearTraceMdc();
        assertThat(MDC.get("liteflow_trace")).isNull();
        try (AutoCloseable scope = liteflowTemplate.withTraceMdcScope(result.getTrace())) {
            assertThat(MDC.get("liteflow_trace")).isNotNull();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertThat(MDC.get("liteflow_trace")).isNull();
    }

    @Test
    @DisplayName("验证 Micrometer 云原生指标度量全真采集 (Counter 与 Timers)")
    void testMicrometerMetricsCollection() {
        assertThat(liteflowMetricsBinder).isNotNull();
        assertThat(liteflowMetricsBinder.getMeterRegistry()).isSameAs(meterRegistry);

        // 1. 执行成功规则链
        liteflowTemplate.execute("normalTraceChain", ObsContext.class);

        // 验证规则链执行计数器
        Counter chainCounter = meterRegistry.find(LiteflowMetricsBinder.METRIC_CHAIN_EXECUTIONS)
                .tag(LiteflowMetricsBinder.TAG_CHAIN_ID, "normalTraceChain")
                .tag(LiteflowMetricsBinder.TAG_STATUS, LiteflowMetricsBinder.STATUS_SUCCESS)
                .counter();
        assertThat(chainCounter).isNotNull();
        assertThat(chainCounter.count()).isGreaterThanOrEqualTo(1.0);

        // 验证规则链执行计时器
        Timer chainTimer = meterRegistry.find(LiteflowMetricsBinder.METRIC_CHAIN_DURATION)
                .tag(LiteflowMetricsBinder.TAG_CHAIN_ID, "normalTraceChain")
                .tag(LiteflowMetricsBinder.TAG_STATUS, LiteflowMetricsBinder.STATUS_SUCCESS)
                .timer();
        assertThat(chainTimer).isNotNull();
        assertThat(chainTimer.count()).isGreaterThanOrEqualTo(1L);

        // 验证单节点执行计时器
        Timer nodeTimerA = meterRegistry.find(LiteflowMetricsBinder.METRIC_NODE_DURATION)
                .tag(LiteflowMetricsBinder.TAG_CHAIN_ID, "normalTraceChain")
                .tag(LiteflowMetricsBinder.TAG_NODE_ID, "obsNodeA")
                .tag(LiteflowMetricsBinder.TAG_STATUS, LiteflowMetricsBinder.STATUS_SUCCESS)
                .timer();
        assertThat(nodeTimerA).isNotNull();
        assertThat(nodeTimerA.count()).isGreaterThanOrEqualTo(1L);

        Timer nodeTimerB = meterRegistry.find(LiteflowMetricsBinder.METRIC_NODE_DURATION)
                .tag(LiteflowMetricsBinder.TAG_CHAIN_ID, "normalTraceChain")
                .tag(LiteflowMetricsBinder.TAG_NODE_ID, "obsNodeB")
                .tag(LiteflowMetricsBinder.TAG_STATUS, LiteflowMetricsBinder.STATUS_SUCCESS)
                .timer();
        assertThat(nodeTimerB).isNotNull();
        assertThat(nodeTimerB.count()).isGreaterThanOrEqualTo(1L);

        // 2. 执行失败规则链
        assertThatThrownBy(() -> liteflowTemplate.execute("failTraceChain", ObsContext.class))
                .isInstanceOf(LiteflowExecutionException.class);

        // 验证失败链路计数器与计时器
        Counter failChainCounter = meterRegistry.find(LiteflowMetricsBinder.METRIC_CHAIN_EXECUTIONS)
                .tag(LiteflowMetricsBinder.TAG_CHAIN_ID, "failTraceChain")
                .tag(LiteflowMetricsBinder.TAG_STATUS, LiteflowMetricsBinder.STATUS_FAIL)
                .counter();
        assertThat(failChainCounter).isNotNull();
        assertThat(failChainCounter.count()).isGreaterThanOrEqualTo(1.0);

        Timer failChainTimer = meterRegistry.find(LiteflowMetricsBinder.METRIC_CHAIN_DURATION)
                .tag(LiteflowMetricsBinder.TAG_CHAIN_ID, "failTraceChain")
                .tag(LiteflowMetricsBinder.TAG_STATUS, LiteflowMetricsBinder.STATUS_FAIL)
                .timer();
        assertThat(failChainTimer).isNotNull();
        assertThat(failChainTimer.count()).isGreaterThanOrEqualTo(1L);

        // 验证失败节点计时器
        Timer failNodeTimer = meterRegistry.find(LiteflowMetricsBinder.METRIC_NODE_DURATION)
                .tag(LiteflowMetricsBinder.TAG_CHAIN_ID, "failTraceChain")
                .tag(LiteflowMetricsBinder.TAG_NODE_ID, "obsFailNode")
                .tag(LiteflowMetricsBinder.TAG_STATUS, LiteflowMetricsBinder.STATUS_FAIL)
                .timer();
        assertThat(failNodeTimer).isNotNull();
        assertThat(failNodeTimer.count()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("验证动态即时 EL 表达式执行并提取轨迹包装")
    void testExecuteElWithTrace() {
        LiteflowTraceResult<ObsContext> result = liteflowTemplate.executeElWithTrace(
                "THEN(obsNodeA, obsNodeB)", ObsContext.class);

        assertThat(result).isNotNull();
        assertThat(result.getContext().getExecutedNodes()).containsExactly("obsNodeA", "obsNodeB");

        LiteflowExecutionTrace trace = result.getTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.isSuccess()).isTrue();
        assertThat(trace.getSteps()).hasSize(2);
        assertThat(trace.getSteps().get(0).getNodeId()).isEqualTo("obsNodeA");
        assertThat(trace.getSteps().get(1).getNodeId()).isEqualTo("obsNodeB");
    }

    @Test
    @DisplayName("验证从原生 LiteflowResponse 提取轨迹对象能力")
    void testExtractTraceFromResponse() {
        LiteflowResponse response = liteflowTemplate.execute2Resp("normalTraceChain", "param", ObsContext.class);

        LiteflowExecutionTrace trace = liteflowTemplate.extractTrace(response);
        assertThat(trace).isNotNull();
        assertThat(trace.isSuccess()).isTrue();
        assertThat(trace.getSteps()).hasSize(2);
        assertThat(trace.getSteps().get(0).getNodeId()).isEqualTo("obsNodeA");
        assertThat(trace.getSteps().get(1).getNodeId()).isEqualTo("obsNodeB");
    }

    @Test
    @DisplayName("验证动态即时 EL 表达式度量标签归一化为 DYNAMIC_EL 避免内存基数爆炸")
    void testDynamicElMetricTagNormalization() {
        // 1. 验证静态工具归一化规则
        assertThat(LiteflowMetricsBinder.normalizeChainId("DYNAMIC_EL[THEN(obsNodeA, obsNodeB)]"))
                .isEqualTo(LiteflowMetricsBinder.TAG_DYNAMIC_EL);
        assertThat(LiteflowMetricsBinder.normalizeChainId("325abadb7b03472e902a9793fee6e971"))
                .isEqualTo(LiteflowMetricsBinder.TAG_DYNAMIC_EL);
        // 静态业务规则链前缀（如 dynamic_pricing）保留原样，不发生过度折叠
        assertThat(LiteflowMetricsBinder.normalizeChainId("dynamic_pricing_chain"))
                .isEqualTo("dynamic_pricing_chain");
        assertThat(LiteflowMetricsBinder.normalizeChainId("THEN(obsNodeA, obsNodeB);"))
                .isEqualTo(LiteflowMetricsBinder.TAG_DYNAMIC_EL);
        assertThat(LiteflowMetricsBinder.normalizeChainId("normalTraceChain"))
                .isEqualTo("normalTraceChain");

        // 2. 编程式记录动态 EL 度量并断言 Micrometer 中标签已被安全归一化为 DYNAMIC_EL
        liteflowMetricsBinder.recordChainExecution("DYNAMIC_EL[THEN(obsNodeA, obsNodeB)]", "SUCCESS", 1000000L);

        Counter dynamicCounter = meterRegistry.find(LiteflowMetricsBinder.METRIC_CHAIN_EXECUTIONS)
                .tag(LiteflowMetricsBinder.TAG_CHAIN_ID, LiteflowMetricsBinder.TAG_DYNAMIC_EL)
                .tag(LiteflowMetricsBinder.TAG_STATUS, LiteflowMetricsBinder.STATUS_SUCCESS)
                .counter();
        assertThat(dynamicCounter).isNotNull();
        assertThat(dynamicCounter.count()).isGreaterThanOrEqualTo(1.0);

        // 3. 执行动态 EL 表达式，验证底层生成的 32 位 UUID 规则链在节点级度量中已被安全归一化为 DYNAMIC_EL
        liteflowTemplate.executeEl("THEN(obsNodeA, obsNodeB);", ObsContext.class);

        Timer nodeTimer = meterRegistry.find(LiteflowMetricsBinder.METRIC_NODE_DURATION)
                .tag(LiteflowMetricsBinder.TAG_CHAIN_ID, LiteflowMetricsBinder.TAG_DYNAMIC_EL)
                .tag(LiteflowMetricsBinder.TAG_NODE_ID, "obsNodeA")
                .timer();
        assertThat(nodeTimer).isNotNull();
        assertThat(nodeTimer.count()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("验证 LiteflowMetricsBinder 实现 DisposableBean 能够安全从 LifeCycleHolder 注销")
    void testMetricsBinderDisposableBeanUnbind() {
        assertThat(com.yomahub.liteflow.lifecycle.LifeCycleHolder.getPostProcessChainExecuteLifeCycleList())
                .contains(liteflowMetricsBinder.getChainLifeCycle());
        assertThat(com.yomahub.liteflow.lifecycle.LifeCycleHolder.getPostProcessNodeExecuteLifeCycleList())
                .contains(liteflowMetricsBinder.getNodeLifeCycle());

        liteflowMetricsBinder.destroy();

        assertThat(com.yomahub.liteflow.lifecycle.LifeCycleHolder.getPostProcessChainExecuteLifeCycleList())
                .doesNotContain(liteflowMetricsBinder.getChainLifeCycle());
        assertThat(com.yomahub.liteflow.lifecycle.LifeCycleHolder.getPostProcessNodeExecuteLifeCycleList())
                .doesNotContain(liteflowMetricsBinder.getNodeLifeCycle());

        // 重新注册回 LifeCycleHolder，避免影响其他单测
        com.yomahub.liteflow.lifecycle.LifeCycleHolder.addLifeCycle(liteflowMetricsBinder.getChainLifeCycle());
        com.yomahub.liteflow.lifecycle.LifeCycleHolder.addLifeCycle(liteflowMetricsBinder.getNodeLifeCycle());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({
            ObsNodeA.class,
            ObsNodeB.class,
            ObsFailNode.class,
            ObsRollbackNodeA.class
    })
    static class TestConfig {

        @Bean
        public SimpleMeterRegistry simpleMeterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    /**
     * 测试可观测业务上下文
     */
    @Data
    public static class ObsContext implements Serializable {
        private final List<String> executedNodes = new ArrayList<>();
        private final List<String> rollbackNodes = new ArrayList<>();
    }

    @Component("obsNodeA")
    public static class ObsNodeA extends BaseNodeComponent<ObsContext> {
        @Override
        public void process(ObsContext context) {
            context.getExecutedNodes().add("obsNodeA");
        }
    }

    @Component("obsNodeB")
    public static class ObsNodeB extends BaseNodeComponent<ObsContext> {
        @Override
        public void process(ObsContext context) {
            context.getExecutedNodes().add("obsNodeB");
        }
    }

    @Component("obsFailNode")
    public static class ObsFailNode extends NodeComponent {
        @Override
        public void process() {
            throw new IllegalStateException("模拟组件异常失败");
        }
    }

    @Component("obsRollbackNodeA")
    public static class ObsRollbackNodeA extends BaseNodeComponent<ObsContext> {
        @Override
        public void process(ObsContext context) {
            context.getExecutedNodes().add("obsRollbackNodeA");
        }

        @Override
        public void rollback(ObsContext context) {
            context.getRollbackNodes().add("obsRollbackNodeA");
        }
    }
}
