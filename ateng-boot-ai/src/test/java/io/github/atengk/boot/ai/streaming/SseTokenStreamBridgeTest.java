package io.github.atengk.boot.ai.streaming;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
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

    private static String extractSseString(SseEmitter.SseEventBuilder builder) {
        Set<ResponseBodyEmitter.DataWithMediaType> set = builder.build();
        StringBuilder sb = new StringBuilder();
        for (ResponseBodyEmitter.DataWithMediaType dataWithMediaType : set) {
            sb.append(dataWithMediaType.getData());
        }
        return sb.toString();
    }
}
