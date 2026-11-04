package io.github.atengk.boot.ai.streaming;

import okhttp3.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * OkHttp 流式连接安全取消拦截器与相关设施单元测试
 *
 * @author Ateng
 * @since 2026-11-04
 */
class StreamCancellationInterceptorTest {

    @AfterEach
    void tearDown() {
        StreamContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("通过 StreamContextHolder 识别 streamId 并自动完成登记与注销")
    void testInterceptWithStreamContextHolder() throws Exception {
        ActiveStreamRegistry registry = new ActiveStreamRegistry();
        StreamCancellationInterceptor interceptor = new StreamCancellationInterceptor(registry);

        StreamContextHolder.setStreamId("stream-ctx-101");

        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        Call call = mock(Call.class);
        Request request = new Request.Builder().url("https://api.openai.com/v1/chat/completions").build();
        ResponseBody responseBody = ResponseBody.create("data: hello", MediaType.get("text/event-stream"));
        Response response = new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseBody)
                .build();

        when(chain.call()).thenReturn(call);
        when(chain.request()).thenReturn(request);

        // 模拟执行链路并在链路中途检查注册表状态
        AtomicBoolean wasActiveDuringCall = new AtomicBoolean(false);
        when(chain.proceed(any(Request.class))).thenAnswer(invocation -> {
            wasActiveDuringCall.set(registry.isActive("stream-ctx-101"));
            return response;
        });

        Response actualResponse = interceptor.intercept(chain);

        assertThat(actualResponse).isNotNull();
        assertThat(wasActiveDuringCall.get()).isTrue();
        // 关键断言：推流长连接未关闭前，注册表中必须保持活跃，以支持后续任意时刻客户端断连中断
        assertThat(registry.isActive("stream-ctx-101")).isTrue();

        // 模拟推流结束，OkHttp 响应流关闭，此时确定性自动注销排空
        actualResponse.close();
        assertThat(registry.isActive("stream-ctx-101")).isFalse();
    }

    @Test
    @DisplayName("通过 HTTP 请求头 X-Stream-Id 兜底提取流式标识")
    void testInterceptWithHeaderFallback() throws Exception {
        ActiveStreamRegistry registry = new ActiveStreamRegistry();
        StreamCancellationInterceptor interceptor = new StreamCancellationInterceptor(registry);

        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        Call call = mock(Call.class);
        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .header(StreamCancellationInterceptor.HEADER_STREAM_ID, "stream-header-202")
                .build();
        ResponseBody responseBody = ResponseBody.create("data: hello", MediaType.get("text/event-stream"));
        Response response = new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseBody)
                .build();

        when(chain.call()).thenReturn(call);
        when(chain.request()).thenReturn(request);

        AtomicBoolean wasActiveDuringCall = new AtomicBoolean(false);
        when(chain.proceed(any(Request.class))).thenAnswer(invocation -> {
            wasActiveDuringCall.set(registry.isActive("stream-header-202"));
            return response;
        });

        Response actualResponse = interceptor.intercept(chain);

        assertThat(wasActiveDuringCall.get()).isTrue();
        // 长连接未关闭前保持活跃
        assertThat(registry.isActive("stream-header-202")).isTrue();

        // 响应流关闭后注销排空
        actualResponse.close();
        assertThat(registry.isActive("stream-header-202")).isFalse();
    }

    @Test
    @DisplayName("流式推流过程中收到取消信号：底层 OkHttp Call 物理触发 cancel()")
    void testStreamCancellationTriggeredInFlight() throws Exception {
        ActiveStreamRegistry registry = new ActiveStreamRegistry();
        StreamCancellationInterceptor interceptor = new StreamCancellationInterceptor(registry);

        StreamContextHolder.setStreamId("stream-abort-303");

        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        Call call = mock(Call.class);
        Request request = new Request.Builder().url("https://api.openai.com/v1/chat/completions").build();

        when(chain.call()).thenReturn(call);
        when(chain.request()).thenReturn(request);

        // 模拟推流进行中客户端断开，外部调用 registry.cancel() 触发切断
        when(chain.proceed(any(Request.class))).thenAnswer(invocation -> {
            boolean cancelled = registry.cancel("stream-abort-303");
            assertThat(cancelled).isTrue();

            when(call.isCanceled()).thenReturn(true);
            throw new IOException("Canceled");
        });

        assertThatThrownBy(() -> interceptor.intercept(chain))
                .isInstanceOf(IOException.class)
                .hasMessage("Canceled");

        // 验证 call.cancel() 被物理调用
        verify(call, times(1)).cancel();
        // 异常退出后注册表依然保证注销排空
        assertThat(registry.isActive("stream-abort-303")).isFalse();
    }

    @Test
    @DisplayName("StreamEventListener 在主线程 callStart 时同步绑定物理句柄")
    void testStreamEventListenerLifecycle() {
        ActiveStreamRegistry registry = new ActiveStreamRegistry();
        StreamEventListener listener = new StreamEventListener(registry);
        Call call = mock(Call.class);

        StreamContextHolder.setStreamId("stream-listener-404");

        // 模拟 OkHttp enqueue() 同步调用 callStart
        listener.callStart(call);
        assertThat(registry.isActive("stream-listener-404")).isTrue();

        // 触发取消
        registry.cancel("stream-listener-404");
        verify(call, times(1)).cancel();

        // 模拟 callEnd
        listener.callEnd(call);
        assertThat(registry.isActive("stream-listener-404")).isFalse();
    }

    @Test
    @DisplayName("StreamPropagatingExecutorService 跨线程透传 streamId 上下文")
    void testStreamPropagatingExecutorService() throws Exception {
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(1);
        taskExecutor.setMaxPoolSize(1);
        taskExecutor.setThreadNamePrefix("test-stream-");
        taskExecutor.initialize();

        StreamPropagatingExecutorService propagatingExecutor = new StreamPropagatingExecutorService(taskExecutor.getThreadPoolExecutor());

        try {
            StreamContextHolder.setStreamId("stream-thread-505");

            AtomicReference<String> workerStreamId = new AtomicReference<>();
            CompletableFuture<Void> future = new CompletableFuture<>();

            propagatingExecutor.execute(() -> {
                workerStreamId.set(StreamContextHolder.getStreamId());
                future.complete(null);
            });

            future.get();
            assertThat(workerStreamId.get()).isEqualTo("stream-thread-505");
        } finally {
            propagatingExecutor.shutdown();
            taskExecutor.shutdown();
        }
    }
}
