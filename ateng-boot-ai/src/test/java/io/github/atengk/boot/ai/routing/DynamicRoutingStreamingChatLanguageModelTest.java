package io.github.atengk.boot.ai.routing;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import io.github.atengk.boot.ai.context.AgentCallContext;
import io.github.atengk.boot.ai.context.AiContextHolder;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.quota.AiQuotaChecker;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.ai.tenant.AiTenantModelResolver;
import io.github.atengk.boot.core.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * 动态路由与多模型切源流式聊天模型代理单元测试
 *
 * @author Ateng
 * @since 2026-11-04
 */
class DynamicRoutingStreamingChatLanguageModelTest {

    private AiModelRegistry registry;
    private StreamingChatLanguageModel defaultModel;
    private StreamingChatLanguageModel customModel;
    private StreamingChatLanguageModel threadModel;

    @BeforeEach
    void setUp() {
        registry = new AiModelRegistry();
        defaultModel = mock(StreamingChatLanguageModel.class);
        customModel = mock(StreamingChatLanguageModel.class);
        threadModel = mock(StreamingChatLanguageModel.class);

        registry.registerDefault(null, defaultModel, "default");
        registry.registerStreamingChatModel("custom-model", customModel);
        registry.registerStreamingChatModel("thread-model", threadModel);
    }

    @AfterEach
    void tearDown() {
        AiContextHolder.clear();
        AgentCallContext.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("缺省情况下路由至默认流式模型")
    void testRouteToDefaultModel() {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            StreamingResponseHandler<AiMessage> handler = invocation.getArgument(1);
            handler.onNext("hello ");
            handler.onNext("default");
            handler.onComplete(Response.from(AiMessage.from("hello default")));
            return null;
        }).when(defaultModel).generate(anyList(), any());

        DynamicRoutingStreamingChatLanguageModel router = new DynamicRoutingStreamingChatLanguageModel(registry, "");
        StringBuilder sb = new StringBuilder();

        router.generate(List.of(UserMessage.from("hi")), new StreamingResponseHandler<>() {
            @Override
            public void onNext(String token) {
                sb.append(token);
            }

            @Override
            public void onComplete(Response<AiMessage> response) {}

            @Override
            public void onError(Throwable error) {}
        });

        assertThat(sb.toString()).isEqualTo("hello default");
        verify(defaultModel).generate(anyList(), any());
        verifyNoInteractions(customModel, threadModel);
    }

    @Test
    @DisplayName("显式配置静态模型时路由至指定模型")
    void testRouteToConfiguredModel() {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            StreamingResponseHandler<AiMessage> handler = invocation.getArgument(1);
            handler.onNext("hello custom");
            handler.onComplete(Response.from(AiMessage.from("hello custom")));
            return null;
        }).when(customModel).generate(anyList(), any());

        DynamicRoutingStreamingChatLanguageModel router = new DynamicRoutingStreamingChatLanguageModel(registry, "custom-model");
        StringBuilder sb = new StringBuilder();

        router.generate(List.of(UserMessage.from("hi")), new StreamingResponseHandler<>() {
            @Override
            public void onNext(String token) {
                sb.append(token);
            }

            @Override
            public void onComplete(Response<AiMessage> response) {}

            @Override
            public void onError(Throwable error) {}
        });

        assertThat(sb.toString()).isEqualTo("hello custom");
        verify(customModel).generate(anyList(), any());
        verifyNoInteractions(defaultModel, threadModel);
    }

    @Test
    @DisplayName("AiContextHolder 线程级动态切源优先级高于静态配置")
    void testRoutePriorityWithThreadContext() {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            StreamingResponseHandler<AiMessage> handler = invocation.getArgument(1);
            handler.onNext("hello thread");
            handler.onComplete(Response.from(AiMessage.from("hello thread")));
            return null;
        }).when(threadModel).generate(anyList(), any());

        DynamicRoutingStreamingChatLanguageModel router = new DynamicRoutingStreamingChatLanguageModel(registry, "custom-model");
        StringBuilder sb = new StringBuilder();

        try (var ignored = AiContextHolder.useModel("thread-model")) {
            router.generate(List.of(UserMessage.from("hi")), new StreamingResponseHandler<>() {
                @Override
                public void onNext(String token) {
                    sb.append(token);
                }

                @Override
                public void onComplete(Response<AiMessage> response) {}

                @Override
                public void onError(Throwable error) {}
            });
            assertThat(sb.toString()).isEqualTo("hello thread");
            verify(threadModel).generate(anyList(), any());
            verifyNoInteractions(defaultModel, customModel);
        }
    }

    @Test
    @DisplayName("结合多租户切源解析器动态解析租户专用模型")
    void testRouteWithTenantResolver() {
        AiTenantModelResolver resolver = (tenantId, defaultTarget) -> "tenant-t1".equals(tenantId) ? "custom-model" : defaultTarget;
        DynamicRoutingStreamingChatLanguageModel router = new DynamicRoutingStreamingChatLanguageModel(registry, "default", resolver);

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            StreamingResponseHandler<AiMessage> handler = invocation.getArgument(1);
            handler.onNext("tenant model reply");
            handler.onComplete(Response.from(AiMessage.from("tenant model reply")));
            return null;
        }).when(customModel).generate(anyList(), any());

        TenantContextHolder.setTenantId("tenant-t1");
        StringBuilder sb = new StringBuilder();

        router.generate(List.of(UserMessage.from("hello")), new StreamingResponseHandler<>() {
            @Override
            public void onNext(String token) {
                sb.append(token);
            }

            @Override
            public void onComplete(Response<AiMessage> response) {}

            @Override
            public void onError(Throwable error) {}
        });

        assertThat(sb.toString()).isEqualTo("tenant model reply");
        verify(customModel).generate(anyList(), any());
    }

    @Test
    @DisplayName("前置配额风控守卫拦截与完成后累计记录配额")
    void testQuotaCheckAndRecordUsage() {
        AiQuotaChecker quotaChecker = mock(AiQuotaChecker.class);
        DynamicRoutingStreamingChatLanguageModel router = new DynamicRoutingStreamingChatLanguageModel(registry, "custom-model", null, quotaChecker);

        TenantContextHolder.setTenantId("tenant-quota-stream");

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            StreamingResponseHandler<AiMessage> handler = invocation.getArgument(1);
            handler.onNext("token-1");
            handler.onComplete(Response.from(AiMessage.from("token-1"), new TokenUsage(10, 20, 30)));
            return null;
        }).when(customModel).generate(anyList(), any());

        router.generate(List.of(UserMessage.from("test prompt")), mock(StreamingResponseHandler.class));

        // 验证前置配额检查
        verify(quotaChecker).checkQuota(eq("tenant-quota-stream"), eq("custom-model"), anyInt());
        // 验证完成时累计记录配额
        verify(quotaChecker).recordUsage("tenant-quota-stream", "custom-model", 30);
    }

    @Test
    @DisplayName("入参复杂对象占位符在 AgentCallContext 中自动替换")
    void testArgumentReplacementsWithContext() {
        DynamicRoutingStreamingChatLanguageModel router = new DynamicRoutingStreamingChatLanguageModel(registry, "custom-model");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            StreamingResponseHandler<AiMessage> handler = invocation.getArgument(1);
            handler.onComplete(Response.from(AiMessage.from("ok")));
            return null;
        }).when(customModel).generate(captor.capture(), any());

        try (var ignored = AgentCallContext.openScope(Map.of("OrderRequest(id=1001)", "{\"id\":\"1001\",\"price\":99.9}"))) {
            router.generate(List.of(UserMessage.from("请审计订单 OrderRequest(id=1001)")), mock(StreamingResponseHandler.class));
        }

        List<ChatMessage> sent = captor.getValue();
        assertThat(sent).hasSize(1);
        assertThat(((UserMessage) sent.get(0)).singleText()).isEqualTo("请审计订单 {\"id\":\"1001\",\"price\":99.9}");
    }

    @Test
    @DisplayName("支持携带工具规范 ToolSpecification 的多参数流式生成")
    void testGenerateWithToolSpecifications() {
        DynamicRoutingStreamingChatLanguageModel router = new DynamicRoutingStreamingChatLanguageModel(registry, "custom-model");

        ToolSpecification toolSpec = ToolSpecification.builder()
                .name("queryOrderStatus")
                .description("查询订单状态")
                .build();

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            StreamingResponseHandler<AiMessage> handler = invocation.getArgument(2);
            handler.onNext("tool result stream");
            handler.onComplete(Response.from(AiMessage.from("tool result stream")));
            return null;
        }).when(customModel).generate(anyList(), anyList(), any());

        StringBuilder sb = new StringBuilder();
        router.generate(List.of(UserMessage.from("查询订单 ORD_001")), List.of(toolSpec), new StreamingResponseHandler<>() {
            @Override
            public void onNext(String token) {
                sb.append(token);
            }

            @Override
            public void onComplete(Response<AiMessage> response) {}

            @Override
            public void onError(Throwable error) {}
        });

        assertThat(sb.toString()).isEqualTo("tool result stream");
        verify(customModel).generate(anyList(), anyList(), any());
    }
}
