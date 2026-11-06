package io.github.atengk.boot.liteflow.core;

import com.yomahub.liteflow.core.FlowExecutor;
import com.yomahub.liteflow.core.NodeComponent;
import com.yomahub.liteflow.flow.FlowBus;
import com.yomahub.liteflow.flow.LiteflowResponse;
import io.github.atengk.boot.liteflow.exception.LiteflowExecutionException;
import io.github.atengk.boot.liteflow.trace.LiteflowTraceResult;
import lombok.Data;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LiteflowTemplate 统一强类型操作门面单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
@SpringBootTest(classes = LiteflowTemplateTest.TestConfig.class, properties = {
        "liteflow.rule-source=classpath:liteflow/template-flow.el.xml",
        "liteflow.enable=true"
})
@DisplayName("LiteflowTemplate 统一强类型操作门面单元测试")
class LiteflowTemplateTest {

    @Autowired
    private LiteflowTemplate liteflowTemplate;

    @Autowired
    private FlowExecutor flowExecutor;

    @AfterEach
    void tearDown() {
        FlowBus.removeChain("dynamicChain", "xmlDynamicChain", "preserveChain");
    }

    @Test
    @DisplayName("验证同步执行规则链并自动强类型解包成功")
    void testExecuteSuccess() {
        SampleTestContext context = liteflowTemplate.execute("testChainSuccess", "testParam", SampleTestContext.class);

        assertThat(context).isNotNull();
        assertThat(context.getData()).isEqualTo("stepA_done");
        assertThat(context.getCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("验证无入参重载方法执行成功")
    void testExecuteWithoutParam() {
        SampleTestContext context = liteflowTemplate.execute("testChainSuccess", SampleTestContext.class);

        assertThat(context).isNotNull();
        assertThat(context.getData()).isEqualTo("stepA_done");
    }

    @Test
    @DisplayName("验证预置上下文实例执行成功并返回同一实例")
    void testExecuteWithContext() {
        SampleTestContext initialContext = new SampleTestContext();
        initialContext.setData("init");

        SampleTestContext resultContext = liteflowTemplate.executeWithContext("testChainSuccess", "param", initialContext);

        assertThat(resultContext).isSameAs(initialContext);
        assertThat(resultContext.getData()).isEqualTo("stepA_done");
        assertThat(resultContext.getCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("验证多上下文执行方法返回原始响应包装")
    void testExecuteMultipleContexts() {
        LiteflowResponse response = liteflowTemplate.execute("testChainSuccess", "param",
                SampleTestContext.class, SecondTestContext.class);

        assertThat(response).isNotNull();
        assertThat(response.isSuccess()).isTrue();
        SampleTestContext context = response.getContextBean(SampleTestContext.class);
        assertThat(context.getData()).isEqualTo("stepA_done");

        LiteflowResponse response2 = liteflowTemplate.executeMultiple("testChainSuccess", "param",
                SampleTestContext.class, SecondTestContext.class);
        assertThat(response2.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("验证多预置上下文实例执行并成功累积业务数据")
    void testExecuteMultipleWithContext() {
        SampleTestContext first = new SampleTestContext();
        first.setData("init_first");
        SecondTestContext second = new SecondTestContext();
        second.setTag("init_second");

        Object[] result = liteflowTemplate.executeMultipleWithContext("testChainSuccess", "param", first, second);

        assertThat(result).isNotNull();
        assertThat(result).containsExactly(first, second);
        assertThat(first.getData()).isEqualTo("stepA_done");
        assertThat(first.getCount()).isEqualTo(1);
        assertThat(second.getTag()).isEqualTo("stepA_second_done");
    }

    @Test
    @DisplayName("验证多预置上下文实例执行并返回带有链路轨迹的结果包装")
    void testExecuteMultipleWithContextWithTrace() {
        SampleTestContext first = new SampleTestContext();
        SecondTestContext second = new SecondTestContext();

        LiteflowTraceResult<Object[]> result = liteflowTemplate.executeMultipleWithContextWithTrace(
                "testChainSuccess", "param", first, second);

        assertThat(result).isNotNull();
        assertThat(result.getContext()).containsExactly(first, second);
        assertThat(result.getTrace()).isNotNull();
        assertThat(result.getTrace().isSuccess()).isTrue();
        assertThat(result.getTrace().getSteps()).hasSize(1);
        assertThat(result.getTrace().getSteps().get(0).getNodeId()).isEqualTo("stepA");
    }

    @Test
    @DisplayName("验证异步流式执行并提取时序轨迹包装")
    void testExecuteAsyncWithTrace() throws Exception {
        CompletableFuture<LiteflowTraceResult<SampleTestContext>> future = liteflowTemplate.executeAsyncWithTrace(
                "testChainSuccess", "param", SampleTestContext.class);

        LiteflowTraceResult<SampleTestContext> result = future.get(5, TimeUnit.SECONDS);

        assertThat(result).isNotNull();
        assertThat(result.getContext().getData()).isEqualTo("stepA_done");
        assertThat(result.getTrace().isSuccess()).isTrue();
        assertThat(result.getTrace().getSteps()).isNotEmpty();
    }

    @Test
    @DisplayName("验证异步流式执行（预置上下文）并提取时序轨迹包装")
    void testExecuteAsyncWithTraceWithContext() throws Exception {
        SampleTestContext context = new SampleTestContext();
        context.setData("init");

        CompletableFuture<LiteflowTraceResult<SampleTestContext>> future = liteflowTemplate.executeAsyncWithTrace(
                "testChainSuccess", "param", context);

        LiteflowTraceResult<SampleTestContext> result = future.get(5, TimeUnit.SECONDS);

        assertThat(result).isNotNull();
        assertThat(result.getContext()).isSameAs(context);
        assertThat(context.getData()).isEqualTo("stepA_done");
        assertThat(result.getTrace().isSuccess()).isTrue();
    }

    @Test
    @DisplayName("验证带超时上限的异步执行在超时触发时中断并抛出 TimeoutException")
    void testExecuteAsyncWithTimeout() {
        liteflowTemplate.reloadChain("slowChain", "THEN(stepSlow);");

        CompletableFuture<SampleTestContext> future = liteflowTemplate.executeAsync(
                "slowChain", "param", SampleTestContext.class, Duration.ofMillis(50));

        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    @DisplayName("验证带超时上限的异步时序轨迹执行在超时触发时抛出 TimeoutException")
    void testExecuteAsyncWithTraceWithTimeout() {
        liteflowTemplate.reloadChain("slowTraceChain", "THEN(stepSlow);");

        CompletableFuture<LiteflowTraceResult<SampleTestContext>> future = liteflowTemplate.executeAsyncWithTrace(
                "slowTraceChain", "param", SampleTestContext.class, Duration.ofMillis(50));

        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    @DisplayName("验证节点异常时自动转译为 LiteflowExecutionException 并携带规则元数据")
    void testExecuteFailureThrowsLiteflowExecutionException() {
        assertThatThrownBy(() -> liteflowTemplate.execute("testChainFailure", "param", SampleTestContext.class))
                .isInstanceOf(LiteflowExecutionException.class)
                .satisfies(ex -> {
                    LiteflowExecutionException e = (LiteflowExecutionException) ex;
                    assertThat(e.getChainId()).isEqualTo("testChainFailure");
                    assertThat(e.getFailedNodeId()).isEqualTo("stepFail");
                    assertThat(e.getCause()).isNotNull();
                    assertThat(e.getMessage()).contains("testChainFailure");
                });
    }

    @Test
    @DisplayName("验证受管异步编排返回 CompletableFuture 并正常解析上下文")
    void testExecuteAsync() throws Exception {
        CompletableFuture<SampleTestContext> future = liteflowTemplate.executeAsync(
                "testChainSuccess", "asyncParam", SampleTestContext.class);

        SampleTestContext context = future.get(5, TimeUnit.SECONDS);

        assertThat(context).isNotNull();
        assertThat(context.getData()).isEqualTo("stepA_done");
    }

    @Test
    @DisplayName("验证受管异步编排（预置上下文）返回 CompletableFuture 并更新上下文")
    void testExecuteAsyncWithContext() throws Exception {
        SampleTestContext initialContext = new SampleTestContext();
        initialContext.setData("init");
        CompletableFuture<SampleTestContext> future = liteflowTemplate.executeAsyncWithContext(
                "testChainSuccess", "asyncParam", initialContext);

        SampleTestContext context = future.get(5, TimeUnit.SECONDS);

        assertThat(context).isSameAs(initialContext);
        assertThat(context.getData()).isEqualTo("stepA_done");
    }

    @Test
    @DisplayName("验证直接即时执行动态 EL 表达式并解包上下文")
    void testExecuteEl() {
        SampleTestContext context = liteflowTemplate.executeEl("THEN(stepA)", "elParam", SampleTestContext.class);

        assertThat(context).isNotNull();
        assertThat(context.getData()).isEqualTo("stepA_done");
    }

    @Test
    @DisplayName("验证直接即时执行动态 EL 表达式（预置上下文）")
    void testExecuteElWithContext() {
        SampleTestContext initialContext = new SampleTestContext();
        initialContext.setData("init");
        SampleTestContext context = liteflowTemplate.executeElWithContext("THEN(stepA)", "elParam", initialContext);

        assertThat(context).isSameAs(initialContext);
        assertThat(context.getData()).isEqualTo("stepA_done");
    }

    @Test
    @DisplayName("验证动态 EL 表达式执行失败时异常完整保留 EL 语句")
    void testExecuteElFailurePreservesElExpression() {
        assertThatThrownBy(() -> liteflowTemplate.executeEl("THEN(stepFail)", SampleTestContext.class))
                .isInstanceOf(LiteflowExecutionException.class)
                .satisfies(ex -> {
                    LiteflowExecutionException e = (LiteflowExecutionException) ex;
                    assertThat(e.getChainId()).contains("DYNAMIC_EL[THEN(stepFail)]");
                    assertThat(e.getMessage()).contains("DYNAMIC_EL[THEN(stepFail)]");
                });
    }

    @Test
    @DisplayName("验证动态热重载单条规则链并即时调用成功")
    void testReloadChainAndExecute() {
        liteflowTemplate.reloadChain("dynamicChain", "THEN(stepA)");

        SampleTestContext context = liteflowTemplate.execute("dynamicChain", SampleTestContext.class);

        assertThat(context).isNotNull();
        assertThat(context.getData()).isEqualTo("stepA_done");
    }

    @Test
    @DisplayName("验证动态热更新全量 XML 规则内容并即时调用成功")
    void testReloadRuleXmlAndExecute() {
        String dynamicRuleXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<flow>\n" +
                "    <chain name=\"xmlDynamicChain\">\n" +
                "        THEN(stepA);\n" +
                "    </chain>\n" +
                "</flow>";

        liteflowTemplate.reloadRule(dynamicRuleXml);

        SampleTestContext context = liteflowTemplate.execute("xmlDynamicChain", SampleTestContext.class);

        assertThat(context).isNotNull();
        assertThat(context.getData()).isEqualTo("stepA_done");
    }

    @Test
    @DisplayName("验证 EL 表达式快速布尔校验")
    void testIsValidEl() {
        assertThat(liteflowTemplate.isValidEl("THEN(stepA);")).isTrue();
        assertThat(liteflowTemplate.isValidEl("THEN(stepA)")).isTrue();
        assertThat(liteflowTemplate.isValidEl("THEN(stepA, stepFail);")).isTrue();
        assertThat(liteflowTemplate.isValidEl("THEN(stepA, nonExistentStep);")).isFalse();
        assertThat(liteflowTemplate.isValidEl("INVALID_SYNTAX(stepA")).isFalse();
        assertThat(liteflowTemplate.isValidEl("")).isFalse();
        assertThat(liteflowTemplate.isValidEl(null)).isFalse();
    }

    @Test
    @DisplayName("验证 EL 表达式结构化语法校验")
    void testValidateEl() {
        LiteflowElValidationResult successResult = liteflowTemplate.validateEl("THEN(stepA);");
        assertThat(successResult).isNotNull();
        assertThat(successResult.isValid()).isTrue();
        assertThat(successResult.getEl()).isEqualTo("THEN(stepA);");
        assertThat(successResult.getErrorMessage()).isNull();

        LiteflowElValidationResult syntaxFailResult = liteflowTemplate.validateEl("THEN(stepA");
        assertThat(syntaxFailResult).isNotNull();
        assertThat(syntaxFailResult.isValid()).isFalse();
        assertThat(syntaxFailResult.getErrorMessage()).isNotEmpty();

        LiteflowElValidationResult missingNodeResult = liteflowTemplate.validateEl("THEN(stepA, nonExistentStep);");
        assertThat(missingNodeResult).isNotNull();
        assertThat(missingNodeResult.isValid()).isFalse();
        assertThat(missingNodeResult.getErrorMessage()).isNotEmpty();

        LiteflowElValidationResult emptyResult = liteflowTemplate.validateEl("");
        assertThat(emptyResult.isValid()).isFalse();
        assertThat(emptyResult.getErrorMessage()).contains("不能为空");
    }

    @Test
    @DisplayName("验证获取当前系统已加载就绪的规则链 ID 集合")
    void testGetLoadedChainIds() {
        java.util.Set<String> chainIds = liteflowTemplate.getLoadedChainIds();
        assertThat(chainIds).isNotNull().isNotEmpty();
        assertThat(chainIds).contains("testChainSuccess", "testChainFailure");
    }

    @Test
    @DisplayName("验证非法 EL 表达式热更新失败且绝不破坏线上既有旧规则链")
    void testReloadChainPreservesOldChainOnInvalidEl() {
        // 1. 初始化并运行有效规则链
        liteflowTemplate.reloadChain("preserveChain", "THEN(stepA);");
        SampleTestContext first = liteflowTemplate.execute("preserveChain", SampleTestContext.class);
        assertThat(first.getData()).isEqualTo("stepA_done");

        // 2. 尝试使用非法语法的 EL 表达式进行更新，必须快速失败
        assertThatThrownBy(() -> liteflowTemplate.reloadChain("preserveChain", "INVALID_SYNTAX(stepA"))
                .isInstanceOf(LiteflowExecutionException.class)
                .hasMessageContaining("EL 表达式语法校验未通过");

        // 3. 核心断言：旧规则链完好无损，依然可以稳定正常执行
        assertThat(liteflowTemplate.containChain("preserveChain")).isTrue();
        SampleTestContext second = liteflowTemplate.execute("preserveChain", SampleTestContext.class);
        assertThat(second.getData()).isEqualTo("stepA_done");
    }

    @Test
    @DisplayName("验证底层 FlowExecutor 官方逃生舱实例暴露无误")
    void testGetFlowExecutor() {
        assertThat(liteflowTemplate.getFlowExecutor()).isSameAs(flowExecutor);
    }

    @Test
    @DisplayName("验证非法入参快速失败拦截")
    void testInvalidArguments() {
        assertThatThrownBy(() -> liteflowTemplate.execute("", SampleTestContext.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chainId 不能为空");

        assertThatThrownBy(() -> liteflowTemplate.execute("testChainSuccess", (Class<SampleTestContext>) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contextClass 不能为空");

        assertThatThrownBy(() -> liteflowTemplate.executeEl("", SampleTestContext.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("el 表达式不能为空");

        assertThatThrownBy(() -> liteflowTemplate.reloadChain("", "THEN(stepA)"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chainId 不能为空");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({StepA.class, StepFail.class, StepSlow.class})
    static class TestConfig {
    }

    @Data
    public static class SampleTestContext {
        private String data;
        private int count;
    }

    @Data
    public static class SecondTestContext {
        private String tag;
    }

    @Component("stepA")
    public static class StepA extends NodeComponent {
        @Override
        public void process() {
            try {
                SampleTestContext context = this.getContextBean(SampleTestContext.class);
                if (context != null) {
                    context.setData("stepA_done");
                    context.setCount(context.getCount() + 1);
                }
            } catch (Exception ignored) {
            }
            try {
                SecondTestContext second = this.getContextBean(SecondTestContext.class);
                if (second != null) {
                    second.setTag("stepA_second_done");
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Component("stepFail")
    public static class StepFail extends NodeComponent {
        @Override
        public void process() {
            throw new IllegalStateException("模拟组件节点执行失败");
        }
    }

    @Component("stepSlow")
    public static class StepSlow extends NodeComponent {
        @Override
        public void process() {
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
