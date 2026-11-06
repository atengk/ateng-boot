package io.github.atengk.boot.ai.streaming;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import io.github.atengk.boot.ai.streaming.event.AgentStreamEvent;
import io.github.atengk.boot.ai.streaming.event.AgentStreamListener;
import io.github.atengk.boot.core.context.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Spring MVC SseEmitter 与 LangChain4j 流式推流桥接器测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class SseTokenStreamBridgeTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
        UserContextHolder.clear();
    }

    @Test
    @DisplayName("正常流式推流：思考链与正文通过独立通道推送到 SseEmitter")
    void testNormalTokenStreaming() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        SseTokenStreamBridge bridge = new SseTokenStreamBridge(emitter);

        bridge.onNext("<think>正在深度思考</think>这是正文回答");

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(2)).send(captor.capture());

        List<SseEmitter.SseEventBuilder> builders = captor.getAllValues();
        assertThat(builders).hasSize(2);

        // 第一个分片为 reasoning
        String event1 = extractSseString(builders.get(0));
        assertThat(event1).contains("event:reasoning");
        assertThat(event1).contains("data:正在深度思考");

        // 第二个分片为 text
        String event2 = extractSseString(builders.get(1));
        assertThat(event2).contains("event:text");
        assertThat(event2).contains("data:这是正文回答");
    }

    @Test
    @DisplayName("推流完成时排空残留分片并发送 done 结束信号")
    void testOnCompleteLifecycle() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        SseTokenStreamBridge bridge = new SseTokenStreamBridge(emitter);

        // 发送未闭合的标签前缀
        bridge.onNext("<think>推导中</th");

        // 触发完成
        bridge.onComplete(Response.from(AiMessage.from("complete")));

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        // 1. "推导中" (reasoning)
        // 2. "</th" (finish reasoning)
        // 3. "[DONE]" (done)
        verify(emitter, times(3)).send(captor.capture());

        List<SseEmitter.SseEventBuilder> builders = captor.getAllValues();
        String doneEvent = extractSseString(builders.get(2));
        assertThat(doneEvent).contains("event:done");
        assertThat(doneEvent).contains("data:[DONE]");

        verify(emitter, times(1)).complete();
    }

    @Test
    @DisplayName("大模型异常时转译异常、发送 error 事件并 completeWithError")
    void testOnErrorHandling() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        SseTokenStreamBridge bridge = new SseTokenStreamBridge(emitter);

        RuntimeException cause = new RuntimeException("HTTP 429 Too Many Requests");
        bridge.onError(cause);

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(1)).send(captor.capture());

        String errorEvent = extractSseString(captor.getValue());
        assertThat(errorEvent).contains("event:error");

        verify(emitter, times(1)).completeWithError(any());
    }

    @Test
    @DisplayName("客户端中断连接触发 IOException 时安全捕获并关闭管道")
    void testClientDisconnectGracefulHandling() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("Broken pipe")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        SseTokenStreamBridge bridge = new SseTokenStreamBridge(emitter);

        assertThatCode(() -> bridge.onNext("hello"))
                .doesNotThrowAnyException();

        verify(emitter, times(1)).complete();

        // 再次调用 onNext 不会再触发任何操作
        bridge.onNext("world");
        verify(emitter, times(1)).complete();
    }

    @Test
    @DisplayName("客户端断开导致发送失败时，自动触发 ActiveStreamRegistry 的物理切断回调")
    void testClientDisconnectTriggersPhysicalCancel() throws Exception {
        ActiveStreamRegistry registry = new ActiveStreamRegistry();
        AtomicBoolean physicallyCancelled = new AtomicBoolean(false);
        registry.register("stream-bridge-100", () -> physicallyCancelled.set(true));

        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("Broken pipe")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        SseTokenStreamBridge bridge = new SseTokenStreamBridge(emitter, "stream-bridge-100", registry);

        bridge.onNext("测试中断推流");

        // 验证物理取消被触发
        assertThat(physicallyCancelled.get()).isTrue();
        assertThat(registry.isActive("stream-bridge-100")).isFalse();
    }

    @Test
    @DisplayName("推流正常完成时，从 ActiveStreamRegistry 安全注销连接")
    void testNormalCompletionCleansUpRegistry() throws Exception {
        ActiveStreamRegistry registry = new ActiveStreamRegistry();
        AtomicBoolean physicallyCancelled = new AtomicBoolean(false);
        registry.register("stream-bridge-200", () -> physicallyCancelled.set(true));

        SseEmitter emitter = mock(SseEmitter.class);
        SseTokenStreamBridge bridge = new SseTokenStreamBridge(emitter, "stream-bridge-200", registry);

        bridge.onNext("顺利推流正文");
        bridge.onComplete(Response.from(AiMessage.from("complete")));

        // 验证正常完成不触发 cancel，但注销现场
        assertThat(physicallyCancelled.get()).isFalse();
        assertThat(registry.isActive("stream-bridge-200")).isFalse();
    }

    @Test
    @DisplayName("四维上下文快照全链路透传：工作线程还原请求线程的 MDC 与 User 上下文")
    void testContextPropagation() throws Exception {
        MDC.put("traceId", "trace-test-8888");
        UserContextHolder.set("user-admin-999");

        SseEmitter emitter = mock(SseEmitter.class);
        SseTokenStreamBridge bridge = new SseTokenStreamBridge(emitter);

        // 主线程清理现场
        MDC.clear();
        UserContextHolder.clear();

        AtomicReference<String> capturedTraceId = new AtomicReference<>();
        AtomicReference<String> capturedUser = new AtomicReference<>();

        doAnswer(invocation -> {
            capturedTraceId.set(MDC.get("traceId"));
            capturedUser.set(UserContextHolder.get());
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        // 在完全独立的异步工作线程中执行 onNext
        CompletableFuture.runAsync(() -> bridge.onNext("测试上下文透传")).get();

        assertThat(capturedTraceId.get()).isEqualTo("trace-test-8888");
        assertThat(capturedUser.get()).isEqualTo("user-admin-999");
    }

    @Test
    @DisplayName("可信 RAG 证据溯源：完成推流时增量下发 event: citation 并在结束前发送 [DONE]")
    void testCitationStreamingOnComplete() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        TextSegment seg1 = TextSegment.from("ateng-boot 遵循纯原生零臃肿依赖设计理念",
                Metadata.from(Map.of("file_name", "architecture.md", "id", "seg-001")));
        List<TextSegment> candidates = Collections.singletonList(seg1);

        SseTokenStreamBridge bridge = new SseTokenStreamBridge(emitter)
                .withCitationCandidates(candidates);

        // 模拟打字机推流正文包含引用 [1]
        bridge.onNext("根据设计规范，");
        bridge.onNext("ateng-boot 遵循纯原生零臃肿依赖设计理念[1]。");

        // 触发完成
        bridge.onComplete(Response.from(AiMessage.from("根据设计规范，ateng-boot 遵循纯原生零臃肿依赖设计理念[1]。")));

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(4)).send(captor.capture());

        List<SseEmitter.SseEventBuilder> builders = captor.getAllValues();
        assertThat(builders).hasSize(4);

        // 1 & 2: event: text
        assertThat(extractSseString(builders.get(0))).contains("event:text");
        assertThat(extractSseString(builders.get(1))).contains("event:text");

        // 3: event: citation
        String citationEvent = extractSseString(builders.get(2));
        assertThat(citationEvent).contains("event:citation");
        assertThat(citationEvent).contains("architecture.md");
        assertThat(citationEvent).contains("seg-001");

        // 4: event: done
        String doneEvent = extractSseString(builders.get(3));
        assertThat(doneEvent).contains("event:done");
        assertThat(doneEvent).contains("data:[DONE]");

        verify(emitter, times(1)).complete();
    }

    @Test
    @DisplayName("onRetrieved 事件：正确下发 rag_retrieved 事件并通知 AgentStreamListener")
    void testOnRetrievedEventAndListener() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        AgentStreamListener listener = mock(AgentStreamListener.class);
        SseTokenStreamBridge bridge = new SseTokenStreamBridge(emitter);
        bridge.addListener(listener);

        TextSegment segment = TextSegment.from("知识库参考文本片段", Metadata.from(Map.of("doc_id", "doc_999")));
        Content content = Content.from(segment);
        List<Content> contents = Collections.singletonList(content);

        bridge.onRetrieved(contents);

        // 验证监听器收到回调
        verify(listener, times(1)).onRetrieved(contents);

        // 验证 SSE 接收到 rag_retrieved 事件
        ArgumentCaptor<SseEmitter.SseEventBuilder> captor = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(1)).send(captor.capture());

        String sseString = extractSseString(captor.getValue());
        assertThat(sseString).contains("event:rag_retrieved");
        assertThat(sseString).contains("知识库参考文本片段");
        assertThat(sseString).contains("doc_999");
    }

    @Test
    @DisplayName("onToolExecuted 事件：顺序下发 tool_call 与 tool_result 双通道事件并通知监听器")
    void testOnToolExecutedEventsAndListener() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        AgentStreamListener listener = mock(AgentStreamListener.class);
        SseTokenStreamBridge bridge = new SseTokenStreamBridge(emitter);
        bridge.addListener(listener);

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call_007")
                .name("queryOrder")
                .arguments("{\"orderId\":\"ORD_8888\"}")
                .build();
        ToolExecution execution = ToolExecution.builder()
                .request(request)
                .result("{\"status\":\"DELIVERED\",\"amount\":500}")
                .build();

        bridge.onToolExecuted(execution);

        // 验证监听器收到回调
        verify(listener, times(1)).onToolExecuted(execution);

        // 验证 SSE 接收到 tool_call 与 tool_result 两条事件
        ArgumentCaptor<SseEmitter.SseEventBuilder> captor = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(2)).send(captor.capture());

        List<SseEmitter.SseEventBuilder> events = captor.getAllValues();
        String callEvent = extractSseString(events.get(0));
        assertThat(callEvent).contains("event:tool_call");
        assertThat(callEvent).contains("call_007");
        assertThat(callEvent).contains("queryOrder");
        assertThat(callEvent).contains("ORD_8888");

        String resultEvent = extractSseString(events.get(1));
        assertThat(resultEvent).contains("event:tool_result");
        assertThat(resultEvent).contains("call_007");
        assertThat(resultEvent).contains("queryOrder");
        assertThat(resultEvent).contains("DELIVERED");
    }

    @Test
    @DisplayName("pipe 桥接：全量绑定 TokenStream 的 5 个生命周期钩子并正确启动")
    void testPipeWiresAllCallbacks() {
        TokenStream tokenStream = mock(TokenStream.class);
        when(tokenStream.onNext(any())).thenReturn(tokenStream);
        when(tokenStream.onRetrieved(any())).thenReturn(tokenStream);
        when(tokenStream.onToolExecuted(any())).thenReturn(tokenStream);
        when(tokenStream.onComplete(any())).thenReturn(tokenStream);
        when(tokenStream.onError(any())).thenReturn(tokenStream);

        SseEmitter emitter = mock(SseEmitter.class);
        AgentStreamListener listener = mock(AgentStreamListener.class);

        SseTokenStreamBridge bridge = SseTokenStreamBridge.pipe(
                tokenStream, emitter, "stream_test_001", null, listener);

        assertThat(bridge.getStreamId()).isEqualTo("stream_test_001");
        assertThat(bridge.getListeners()).contains(listener);

        // 验证 5 个回调方法均被正确链式绑定
        verify(tokenStream, times(1)).onNext(any());
        verify(tokenStream, times(1)).onRetrieved(any());
        verify(tokenStream, times(1)).onToolExecuted(any());
        verify(tokenStream, times(1)).onComplete(any());
        verify(tokenStream, times(1)).onError(any());
        verify(tokenStream, times(1)).start();
    }

    @Test
    @DisplayName("AgentStreamListener 默认契约与 AgentStreamEvent 结构化载荷完整性")
    void testAgentStreamEventAndDefaultListenerContract() {
        List<AgentStreamEvent> capturedEvents = new java.util.ArrayList<>();
        AgentStreamListener defaultListener = new AgentStreamListener() {
            @Override
            public void onEvent(AgentStreamEvent event) {
                capturedEvents.add(event);
            }
        };

        defaultListener.onReasoning("推理片段");
        defaultListener.onText("正文片段");

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("t-1")
                .name("testTool")
                .arguments("{}")
                .build();
        ToolExecution execution = ToolExecution.builder()
                .request(request)
                .result("success")
                .build();
        defaultListener.onToolExecuted(execution);

        defaultListener.onComplete(null);
        defaultListener.onError(new RuntimeException("boom"));

        assertThat(capturedEvents).hasSize(6);
        assertThat(capturedEvents.get(0).eventType()).isEqualTo("reasoning");
        assertThat(capturedEvents.get(0).data()).isEqualTo("推理片段");

        assertThat(capturedEvents.get(1).eventType()).isEqualTo("text");
        assertThat(capturedEvents.get(1).data()).isEqualTo("正文片段");

        assertThat(capturedEvents.get(2).eventType()).isEqualTo("tool_call");
        assertThat(capturedEvents.get(2).data()).isInstanceOf(AgentStreamEvent.ToolCallPayload.class);

        assertThat(capturedEvents.get(3).eventType()).isEqualTo("tool_result");
        assertThat(capturedEvents.get(3).data()).isInstanceOf(AgentStreamEvent.ToolResultPayload.class);

        assertThat(capturedEvents.get(4).eventType()).isEqualTo("done");
        assertThat(capturedEvents.get(5).eventType()).isEqualTo("error");
    }

    private static String extractSseString(SseEmitter.SseEventBuilder builder) {
        Set<ResponseBodyEmitter.DataWithMediaType> set = builder.build();
        StringBuilder sb = new StringBuilder();
        for (ResponseBodyEmitter.DataWithMediaType dataWithMediaType : set) {
            sb.append(dataWithMediaType.getData());
        }
        return sb.toString();
    }
}
