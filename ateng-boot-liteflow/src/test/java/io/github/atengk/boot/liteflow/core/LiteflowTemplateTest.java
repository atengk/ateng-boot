package io.github.atengk.boot.liteflow.core;

import com.yomahub.liteflow.core.FlowExecutor;
import com.yomahub.liteflow.core.NodeComponent;
import com.yomahub.liteflow.flow.LiteflowResponse;
import io.github.atengk.boot.liteflow.exception.LiteflowExecutionException;
import lombok.Data;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
    @DisplayName("验证直接即时执行动态 EL 表达式并解包上下文")
    void testExecuteEl() {
        SampleTestContext context = liteflowTemplate.executeEl("THEN(stepA)", "elParam", SampleTestContext.class);

        assertThat(context).isNotNull();
        assertThat(context.getData()).isEqualTo("stepA_done");
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
    @Import({StepA.class, StepFail.class})
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
            SampleTestContext context = this.getContextBean(SampleTestContext.class);
            if (context != null) {
                context.setData("stepA_done");
                context.setCount(context.getCount() + 1);
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
}
