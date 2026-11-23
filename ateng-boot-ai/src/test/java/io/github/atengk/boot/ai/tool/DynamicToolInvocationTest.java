package io.github.atengk.boot.ai.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.tool.ToolExecutor;
import io.github.atengk.boot.ai.mcp.McpClientAdapter;
import io.github.atengk.boot.ai.mcp.McpClientRegistry;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.ai.template.AiTemplate;
import io.github.atengk.boot.core.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * 编程式动态工具调用与沙箱自愈机制单元测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
class DynamicToolInvocationTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    public static class DynamicInventoryTool {

        @Tool("根据商品编号查询库存数量")
        public int queryStock(@P("商品编号") String skuId) {
            if ("OUT_OF_STOCK".equals(skuId)) {
                throw new BusinessException(10001, "商品已售罄无库存");
            }
            if ("INVALID_ARG".equals(skuId)) {
                throw new IllegalArgumentException("商品编号格式错误");
            }
            return 88;
        }
    }

    @Test
    @DisplayName("测试 ToolExecutionSandbox.wrapToolBeans 混合解析 POJO、MCP 适配器与注册中心")
    void testWrapToolBeans() {
        DynamicInventoryTool pojoTool = new DynamicInventoryTool();

        McpClientAdapter mockAdapter = mock(McpClientAdapter.class);
        when(mockAdapter.getServerName()).thenReturn("test-mcp");
        ToolSpecification mcpSpec = ToolSpecification.builder().name("mcp_tool").build();
        ToolExecutor mcpExecutor = (req, mem) -> "mcp_ok";
        when(mockAdapter.getTools()).thenReturn(Map.of(mcpSpec, mcpExecutor));

        McpClientRegistry registry = new McpClientRegistry();
        McpClientAdapter regAdapter = mock(McpClientAdapter.class);
        when(regAdapter.getServerName()).thenReturn("reg-mcp");
        ToolSpecification regSpec = ToolSpecification.builder().name("reg_tool").build();
        when(regAdapter.getTools()).thenReturn(Map.of(regSpec, (req, mem) -> "reg_ok"));
        registry.register("reg-mcp", regAdapter);

        Map<ToolSpecification, ToolExecutor> tools = ToolExecutionSandbox.wrapToolBeans(
                List.of(pojoTool, mockAdapter, registry)
        );

        assertThat(tools).hasSize(3);
        List<String> names = tools.keySet().stream().map(ToolSpecification::name).toList();
        assertThat(names).containsExactlyInAnyOrder("queryStock", "mcp_tool", "reg_tool");
    }

    @Test
    @DisplayName("测试 AiTemplate.chatWithTools 成功通过 POJO 动态工具调用并完成自愈回答")
    @SuppressWarnings("unchecked")
    void testChatWithToolsSelfHealing() {
        AiModelRegistry modelRegistry = new AiModelRegistry();
        ChatLanguageModel chatModel = mock(ChatLanguageModel.class);
        modelRegistry.registerDefault(chatModel, null, "default");

        AiTemplate aiTemplate = new AiTemplate(modelRegistry);

        // 第一次调用 LLM 触发工具调用 queryStock(skuId: OUT_OF_STOCK)
        // 第二次调用 LLM 接收工具返回的业务拦截信息并输出自愈回答
        when(chatModel.generate(anyList(), anyList())).thenReturn(
                Response.from(AiMessage.from(ToolExecutionRequest.builder()
                        .id("call_stock_1")
                        .name("queryStock")
                        .arguments("{\"skuId\": \"OUT_OF_STOCK\"}")
                        .build())),
                Response.from(AiMessage.from("该商品目前已售罄，建议您查看其他同类商品"))
        );

        DynamicInventoryTool inventoryTool = new DynamicInventoryTool();
        String reply = aiTemplate.chatWithTools("查询库存", inventoryTool);

        assertThat(reply).isEqualTo("该商品目前已售罄，建议您查看其他同类商品");

        // 验证 LLM 第二次调用收到了包含业务错误码与错误信息的自愈反馈
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatModel, times(2)).generate(captor.capture(), anyList());
        List<ChatMessage> secondMessages = captor.getAllValues().get(1);

        ToolExecutionResultMessage resultMessage = (ToolExecutionResultMessage) secondMessages.stream()
                .filter(m -> m instanceof ToolExecutionResultMessage)
                .findFirst()
                .orElseThrow();

        assertThat(resultMessage.text()).contains("工具执行业务拦截: [10001] 商品已售罄无库存");
    }

    @Test
    @DisplayName("测试 SandboxedToolExecutor 委托执行模式下的异常拦截与自愈转译")
    void testSandboxedToolExecutorDelegate() {
        ToolExecutor throwingExecutor = (req, mem) -> {
            throw new BusinessException(10001, "远程服务连接超时");
        };

        ToolExecutor sandboxed = SandboxedToolExecutor.wrap("remote_fetch", throwingExecutor);
        String result = sandboxed.execute(ToolExecutionRequest.builder().name("remote_fetch").build(), null);

        assertThat(result).contains("工具执行业务拦截: [10001] 远程服务连接超时");
    }

    @Test
    @DisplayName("测试 AiTemplate.streamWithTools 流式管道接入与推流")
    @SuppressWarnings("unchecked")
    void testStreamWithTools() {
        AiModelRegistry modelRegistry = new AiModelRegistry();
        ChatLanguageModel chatModel = mock(ChatLanguageModel.class);
        StreamingChatLanguageModel streamingModel = mock(StreamingChatLanguageModel.class);
        modelRegistry.registerDefault(chatModel, streamingModel, "default");

        AiTemplate aiTemplate = new AiTemplate(modelRegistry);
        DynamicInventoryTool inventoryTool = new DynamicInventoryTool();

        doAnswer(invocation -> {
            StreamingResponseHandler<AiMessage> handler = invocation.getArgument(2);
            handler.onNext("查询结果：");
            handler.onNext("库存充足");
            handler.onComplete(Response.from(AiMessage.from("查询结果：库存充足")));
            return null;
        }).when(streamingModel).generate(anyList(), anyList(), any(StreamingResponseHandler.class));

        SseEmitter emitter = new SseEmitter(5000L);
        aiTemplate.streamWithTools("查询库存", List.of(inventoryTool), emitter);

        verify(streamingModel).generate(anyList(), anyList(), any(StreamingResponseHandler.class));
    }
}
