package io.github.atengk.boot.ai.routing;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import io.github.atengk.boot.ai.context.AgentCallContext;
import io.github.atengk.boot.ai.context.AiContextHolder;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * 动态路由与多模型切源聊天模型代理单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class DynamicRoutingChatLanguageModelTest {

    private AiModelRegistry registry;
    private ChatLanguageModel defaultModel;
    private ChatLanguageModel customModel;
    private ChatLanguageModel threadModel;

    @BeforeEach
    void setUp() {
        registry = new AiModelRegistry();
        defaultModel = mock(ChatLanguageModel.class);
        customModel = mock(ChatLanguageModel.class);
        threadModel = mock(ChatLanguageModel.class);

        registry.registerDefault(defaultModel, null, "default");
        registry.register("custom-model", customModel, null);
        registry.register("thread-model", threadModel, null);
    }

    @AfterEach
    void tearDown() {
        AiContextHolder.clear();
        AgentCallContext.clear();
    }

    @Test
    @DisplayName("缺省情况下路由至默认模型")
    void testRouteToDefaultModel() {
        when(defaultModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("hello from default")));

        DynamicRoutingChatLanguageModel router = new DynamicRoutingChatLanguageModel(registry, "");
        Response<AiMessage> response = router.generate(List.of(UserMessage.from("hi")));

        assertThat(response.content().text()).isEqualTo("hello from default");
        verify(defaultModel).generate(anyList());
        verifyNoInteractions(customModel, threadModel);
    }

    @Test
    @DisplayName("显式配置静态模型时路由至指定模型")
    void testRouteToConfiguredModel() {
        when(customModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("hello from custom")));

        DynamicRoutingChatLanguageModel router = new DynamicRoutingChatLanguageModel(registry, "custom-model");
        Response<AiMessage> response = router.generate(List.of(UserMessage.from("hi")));

        assertThat(response.content().text()).isEqualTo("hello from custom");
        verify(customModel).generate(anyList());
        verifyNoInteractions(defaultModel, threadModel);
    }

    @Test
    @DisplayName("AiContextHolder 线程级动态切源优先级高于静态配置")
    void testRoutePriorityWithThreadContext() {
        when(threadModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("hello from thread")));

        DynamicRoutingChatLanguageModel router = new DynamicRoutingChatLanguageModel(registry, "custom-model");

        try (var ignored = AiContextHolder.useModel("thread-model")) {
            Response<AiMessage> response = router.generate(List.of(UserMessage.from("hi")));
            assertThat(response.content().text()).isEqualTo("hello from thread");
            verify(threadModel).generate(anyList());
            verifyNoInteractions(defaultModel, customModel);
        }

        // 退出上下文后恢复为 custom-model
        when(customModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("back to custom")));
        Response<AiMessage> backResponse = router.generate(List.of(UserMessage.from("hi again")));
        assertThat(backResponse.content().text()).isEqualTo("back to custom");
    }

    @Test
    @DisplayName("在当前上下文中自动替换复合对象的 toString 标记为纯 JSON 字符串")
    void testArgumentReplacementInPrompt() {
        when(defaultModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("ok")));

        DynamicRoutingChatLanguageModel router = new DynamicRoutingChatLanguageModel(registry, "");

        Map<String, String> replacements = Map.of(
                "OrderReq[id=1001, amount=99.9]",
                "{\"id\":1001,\"amount\":99.9}"
        );

        try (var ignored = AgentCallContext.openScope(replacements)) {
            router.generate(List.of(UserMessage.from("审核订单: OrderReq[id=1001, amount=99.9]")));
        }

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(defaultModel).generate(captor.capture());

        List messages = captor.getValue();
        UserMessage um = (UserMessage) messages.get(0);
        assertThat(um.singleText()).isEqualTo("审核订单: {\"id\":1001,\"amount\":99.9}");
    }

    @Test
    @DisplayName("自动剥除模型响应中的 Markdown ```json 代码块外壳")
    void testAutoSanitizeMarkdownResponse() {
        String markdownOutput = """
                ```json
                {"status":"PASSED","score":95}
                ```
                """;
        when(defaultModel.generate(anyList())).thenReturn(Response.from(AiMessage.from(markdownOutput)));

        DynamicRoutingChatLanguageModel router = new DynamicRoutingChatLanguageModel(registry, "");
        Response<AiMessage> response = router.generate(List.of(UserMessage.from("audit")));

        assertThat(response.content().text()).isEqualTo("{\"status\":\"PASSED\",\"score\":95}");
    }

    @Test
    @DisplayName("非 JSON 的普通 Markdown 代码块（如 Java/SQL）得到严格保护，不会被截断或脱壳")
    void testPreserveNonJsonMarkdownCodeBlocks() {
        String javaCodeOutput = """
                以下是 Java 示例代码：
                ```java
                public class Hello {
                    public static void main(String[] args) {
                        System.out.println("Hello, World!");
                    }
                }
                ```
                祝你编程愉快！
                """;
        when(defaultModel.generate(anyList())).thenReturn(Response.from(AiMessage.from(javaCodeOutput)));

        DynamicRoutingChatLanguageModel router = new DynamicRoutingChatLanguageModel(registry, "");
        Response<AiMessage> response = router.generate(List.of(UserMessage.from("code")));

        // 保持原始完整内容，不被错误判定为 JSON 或截取大括号
        assertThat(response.content().text()).isEqualTo(javaCodeOutput);
    }

    @Test
    @DisplayName("AgentCallContext 支持嵌套作用域安全入栈与退栈回溯")
    void testNestedAgentCallContextScopeRestoration() {
        Map<String, String> outer = Map.of("OuterMarker", "{\"layer\":1}");
        Map<String, String> inner = Map.of("InnerMarker", "{\"layer\":2}");

        try (var outerScope = AgentCallContext.openScope(outer)) {
            assertThat(AgentCallContext.applyReplacements("Call OuterMarker"))
                    .isEqualTo("Call {\"layer\":1}");

            try (var innerScope = AgentCallContext.openScope(inner)) {
                // 内部作用域同时可见外层与内层
                assertThat(AgentCallContext.applyReplacements("Call OuterMarker and InnerMarker"))
                        .isEqualTo("Call {\"layer\":1} and {\"layer\":2}");
            }

            // 退出内部作用域后，精准恢复外层上下文
            assertThat(AgentCallContext.applyReplacements("Call OuterMarker and InnerMarker"))
                    .isEqualTo("Call {\"layer\":1} and InnerMarker");
        }

        // 退出外层作用域后彻底清理
        assertThat(AgentCallContext.applyReplacements("Call OuterMarker"))
                .isEqualTo("Call OuterMarker");
    }
}
