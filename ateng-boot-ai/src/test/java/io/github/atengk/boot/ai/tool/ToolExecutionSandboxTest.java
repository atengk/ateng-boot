package io.github.atengk.boot.ai.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.ToolExecutor;
import io.github.atengk.boot.ai.annotation.AiAgent;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.registrar.AiAgentFactoryBean;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * 局部受管工具沙箱 ToolExecutionSandbox 与 SandboxedToolExecutor 单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class ToolExecutionSandboxTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    public record OrderDetail(String orderId, double amount, String status) {}

    public static class SampleOrderTools {

        @Tool("根据订单号查询订单状态")
        public String queryStatus(@P("订单号") String orderId) {
            if ("BIZ_ERROR".equals(orderId)) {
                throw new BusinessException(10001, "订单余额不足");
            }
            if ("PARAM_ERROR".equals(orderId)) {
                throw new IllegalArgumentException("订单号格式非法");
            }
            if ("SYS_ERROR".equals(orderId)) {
                throw new RuntimeException("外部系统连接超时");
            }
            if ("FATAL_OOM".equals(orderId)) {
                throw new OutOfMemoryError("模拟系统级内存溢出");
            }
            return "SUCCESS";
        }

        @Tool("获取订单详情复合对象")
        public OrderDetail getDetail(@P("订单号") String orderId) {
            return new OrderDetail(orderId, 199.9, "PAID");
        }

        @Tool("取消订单")
        public void cancelOrder(@P("订单号") String orderId) {
            // void method
        }

        @Tool("返回空值方法")
        public String returnNull(@P("订单号") String orderId) {
            return null;
        }

        public String nonToolMethod() {
            return "Not a tool";
        }
    }

    public static class DuplicateOrderTools {
        @Tool("根据订单号查询订单状态")
        public String queryStatus(@P("订单号") String orderId) {
            return "DUPLICATE";
        }
    }

    public static class NoToolBean {
        public void doNothing() {}
    }

    @AiAgent(tools = {SampleOrderTools.class})
    public interface OrderAssistantAgent {
        @SystemMessage("你是一个订单助手")
        String process(@UserMessage String message);
    }

    @Test
    @DisplayName("正常工具调用 - 字符串返回")
    void testNormalToolExecutionString() throws Exception {
        SampleOrderTools target = new SampleOrderTools();
        Method method = SampleOrderTools.class.getMethod("queryStatus", String.class);
        SandboxedToolExecutor executor = new SandboxedToolExecutor(target, method, "queryStatus");

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call_1")
                .name("queryStatus")
                .arguments("{\"orderId\": \"ORD_001\"}")
                .build();

        MDC.put("traceId", "trace-test-001");
        String result = executor.execute(request, "mem1");
        assertThat(result).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("正常工具调用 - DTO 对象序列化返回")
    void testNormalToolExecutionObject() throws Exception {
        SampleOrderTools target = new SampleOrderTools();
        Method method = SampleOrderTools.class.getMethod("getDetail", String.class);
        SandboxedToolExecutor executor = new SandboxedToolExecutor(target, method, "getDetail");

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call_2")
                .name("getDetail")
                .arguments("{\"orderId\": \"ORD_888\"}")
                .build();

        String result = executor.execute(request, "mem1");
        assertThat(result).contains("\"orderId\":\"ORD_888\"")
                .contains("\"amount\":199.9")
                .contains("\"status\":\"PAID\"");
    }

    @Test
    @DisplayName("正常工具调用 - void 方法与 null 返回值")
    void testNormalToolExecutionVoidAndNull() throws Exception {
        SampleOrderTools target = new SampleOrderTools();
        Method cancelMethod = SampleOrderTools.class.getMethod("cancelOrder", String.class);
        SandboxedToolExecutor cancelExecutor = new SandboxedToolExecutor(target, cancelMethod, "cancelOrder");

        ToolExecutionRequest cancelReq = ToolExecutionRequest.builder()
                .name("cancelOrder")
                .arguments("{\"orderId\": \"ORD_111\"}")
                .build();
        assertThat(cancelExecutor.execute(cancelReq, null)).isEqualTo("Success");

        Method nullMethod = SampleOrderTools.class.getMethod("returnNull", String.class);
        SandboxedToolExecutor nullExecutor = new SandboxedToolExecutor(target, nullMethod, "returnNull");
        ToolExecutionRequest nullReq = ToolExecutionRequest.builder()
                .name("returnNull")
                .arguments("{\"orderId\": \"ORD_111\"}")
                .build();
        assertThat(nullExecutor.execute(nullReq, null)).isEqualTo("null");
    }

    @Test
    @DisplayName("异常自愈反思 - 拦截 BusinessException 转译友好提示")
    void testBusinessExceptionSelfCorrection() throws Exception {
        SampleOrderTools target = new SampleOrderTools();
        Method method = SampleOrderTools.class.getMethod("queryStatus", String.class);
        SandboxedToolExecutor executor = new SandboxedToolExecutor(target, method, "queryStatus");

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("queryStatus")
                .arguments("{\"orderId\": \"BIZ_ERROR\"}")
                .build();

        String result = executor.execute(request, null);
        assertThat(result).isEqualTo("工具执行业务拦截: [10001] 订单余额不足");
    }

    @Test
    @DisplayName("异常自愈反思 - 拦截 IllegalArgumentException 转译参数校验提示")
    void testIllegalArgumentExceptionSelfCorrection() throws Exception {
        SampleOrderTools target = new SampleOrderTools();
        Method method = SampleOrderTools.class.getMethod("queryStatus", String.class);
        SandboxedToolExecutor executor = new SandboxedToolExecutor(target, method, "queryStatus");

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("queryStatus")
                .arguments("{\"orderId\": \"PARAM_ERROR\"}")
                .build();

        String result = executor.execute(request, null);
        assertThat(result).isEqualTo("工具入参校验失败: 订单号格式非法");
    }

    @Test
    @DisplayName("异常自愈反思 - 拦截通用 RuntimeException 转译执行失败提示")
    void testRuntimeExceptionSelfCorrection() throws Exception {
        SampleOrderTools target = new SampleOrderTools();
        Method method = SampleOrderTools.class.getMethod("queryStatus", String.class);
        SandboxedToolExecutor executor = new SandboxedToolExecutor(target, method, "queryStatus");

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("queryStatus")
                .arguments("{\"orderId\": \"SYS_ERROR\"}")
                .build();

        String result = executor.execute(request, null);
        assertThat(result).isEqualTo("工具执行失败: 外部系统连接超时");
    }

    @Test
    @DisplayName("致命错误防卫 - OutOfMemoryError 坚决 Fail-Fast 不吞咽")
    void testFatalErrorFailFast() throws Exception {
        SampleOrderTools target = new SampleOrderTools();
        Method method = SampleOrderTools.class.getMethod("queryStatus", String.class);
        SandboxedToolExecutor executor = new SandboxedToolExecutor(target, method, "queryStatus");

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("queryStatus")
                .arguments("{\"orderId\": \"FATAL_OOM\"}")
                .build();

        assertThatThrownBy(() -> executor.execute(request, null))
                .isInstanceOf(OutOfMemoryError.class)
                .hasMessageContaining("模拟系统级内存溢出");
    }

    @Test
    @DisplayName("wrapToolBean - 忽略非 @Tool 方法并正确包装所有工具")
    void testWrapToolBean() {
        SampleOrderTools target = new SampleOrderTools();
        Map<ToolSpecification, ToolExecutor> toolsMap = ToolExecutionSandbox.wrapToolBean(target);

        assertThat(toolsMap).hasSize(4);
        List<String> toolNames = toolsMap.keySet().stream().map(ToolSpecification::name).toList();
        assertThat(toolNames).containsExactlyInAnyOrder("queryStatus", "getDetail", "cancelOrder", "returnNull");
        assertThat(toolNames).doesNotContain("nonToolMethod");

        assertThat(ToolExecutionSandbox.wrapToolBean(null)).isEmpty();
    }

    @Test
    @DisplayName("buildSandboxedTools - Spring 容器缺失 Tool Bean 抛出 AiException 阻断启动")
    void testBuildSandboxedToolsMissingBean() {
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBean(SampleOrderTools.class))
                .thenThrow(new NoSuchBeanDefinitionException(SampleOrderTools.class));

        assertThatThrownBy(() -> ToolExecutionSandbox.buildSandboxedTools(
                applicationContext,
                new Class<?>[]{SampleOrderTools.class},
                "OrderAssistant"))
                .isInstanceOf(AiException.class)
                .hasMessageContaining("未找到对应 Bean")
                .matches(e -> ((AiException) e).getCode().equals(AiResultCode.AI_AGENT_CONFIG_ERROR.getCode()));
    }

    @Test
    @DisplayName("buildSandboxedTools - 检测到同名工具方法冲突阻断启动")
    void testBuildSandboxedToolsDuplicateConflict() {
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBean(SampleOrderTools.class)).thenReturn(new SampleOrderTools());
        when(applicationContext.getBean(DuplicateOrderTools.class)).thenReturn(new DuplicateOrderTools());

        assertThatThrownBy(() -> ToolExecutionSandbox.buildSandboxedTools(
                applicationContext,
                new Class<?>[]{SampleOrderTools.class, DuplicateOrderTools.class},
                "OrderAssistant"))
                .isInstanceOf(AiException.class)
                .hasMessageContaining("存在同名冲突: [queryStatus]")
                .matches(e -> ((AiException) e).getCode().equals(AiResultCode.AI_AGENT_CONFIG_ERROR.getCode()));
    }

    @Test
    @DisplayName("buildSandboxedTools - 空输入保底返回")
    void testBuildSandboxedToolsEmpty() {
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        assertThat(ToolExecutionSandbox.buildSandboxedTools(applicationContext, null, "Agent")).isEmpty();
        assertThat(ToolExecutionSandbox.buildSandboxedTools(applicationContext, new Class<?>[0], "Agent")).isEmpty();
    }

    @Test
    @DisplayName("AiAgentFactoryBean 整合测试 - 沙箱拦截后大模型获取反馈信息并完成自愈回答")
    @SuppressWarnings("unchecked")
    void testFactoryBeanToolExecutionSandboxIntegration() throws Exception {
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        AiModelRegistry registry = new AiModelRegistry();
        ChatLanguageModel chatModel = mock(ChatLanguageModel.class);
        registry.registerDefault(chatModel, null, "default");

        when(applicationContext.getBean(AiModelRegistry.class)).thenReturn(registry);
        when(applicationContext.getBean(SampleOrderTools.class)).thenReturn(new SampleOrderTools());

        // 第一次调用 LLM 触发工具调用 queryStatus(orderId: BIZ_ERROR)
        // 第二次调用 LLM 接收工具返回的业务拦截信息并输出最终诊断结果
        when(chatModel.generate(anyList(), anyList())).thenReturn(
                Response.from(AiMessage.from(ToolExecutionRequest.builder()
                        .id("call_101")
                        .name("queryStatus")
                        .arguments("{\"orderId\": \"BIZ_ERROR\"}")
                        .build())),
                Response.from(AiMessage.from("经核实该订单余额不足，请充值后重试"))
        );

        AiAgentFactoryBean<OrderAssistantAgent> factoryBean = new AiAgentFactoryBean<>();
        factoryBean.setAgentInterface(OrderAssistantAgent.class);
        factoryBean.setApplicationContext(applicationContext);
        factoryBean.setTools(new Class<?>[]{SampleOrderTools.class});
        factoryBean.afterPropertiesSet();

        OrderAssistantAgent agent = factoryBean.getObject();
        assertThat(agent).isNotNull();

        String answer = agent.process("查询状态");
        assertThat(answer).isEqualTo("经核实该订单余额不足，请充值后重试");

        // 验证 LLM 第二次调用确实收到了自愈反馈字符串
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatModel, times(2)).generate(captor.capture(), anyList());
        List<ChatMessage> secondMessages = captor.getAllValues().get(1);

        ToolExecutionResultMessage resultMessage = (ToolExecutionResultMessage) secondMessages.stream()
                .filter(m -> m instanceof ToolExecutionResultMessage)
                .findFirst()
                .orElseThrow();

        assertThat(resultMessage.text()).isEqualTo("工具执行业务拦截: [10001] 订单余额不足");
    }
}
