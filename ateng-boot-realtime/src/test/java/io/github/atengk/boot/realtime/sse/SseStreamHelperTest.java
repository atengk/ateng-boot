package io.github.atengk.boot.realtime.sse;

import io.github.atengk.boot.realtime.model.RealtimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

/**
 * SSE 专职命令式流助手单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("SseStreamHelper 打字机流助手测试")
class SseStreamHelperTest {

    @Test
    @DisplayName("创建 SseEmitter 超时时间设置")
    void testCreateEmitter() {
        SseEmitter defaultEmitter = SseStreamHelper.create();
        assertThat(defaultEmitter).isNotNull();
        assertThat(defaultEmitter.getTimeout()).isEqualTo(Duration.ofMinutes(30).toMillis());

        SseEmitter customEmitter = SseStreamHelper.create(Duration.ofSeconds(15));
        assertThat(customEmitter.getTimeout()).isEqualTo(15_000L);

        SseEmitter zeroEmitter = SseStreamHelper.create(Duration.ZERO);
        assertThat(zeroEmitter.getTimeout()).isEqualTo(0L);
    }

    @Test
    @DisplayName("大模型打字机流式 Token 分块推送")
    void testSendChunk() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);

        boolean sent1 = SseStreamHelper.sendChunk(emitter, "Hello");
        boolean sent2 = SseStreamHelper.sendChunk(emitter, " World");

        assertThat(sent1).isTrue();
        assertThat(sent2).isTrue();
        verify(emitter, times(1)).send("Hello");
        verify(emitter, times(1)).send(" World");

        // 空值校验
        assertThat(SseStreamHelper.sendChunk(null, "chunk")).isFalse();
        assertThat(SseStreamHelper.sendChunk(emitter, null)).isFalse();
    }

    @Test
    @DisplayName("发送具名事件与 POJO 序列化数据")
    void testSendEvent() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);

        boolean sent = SseStreamHelper.sendEvent(emitter, "progress", Map.of("percent", 80));
        assertThat(sent).isTrue();
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));

        // 发送 RealtimeMessage
        RealtimeMessage<String> message = RealtimeMessage.of("chat", "content-123");
        boolean msgSent = SseStreamHelper.sendEvent(emitter, message);
        assertThat(msgSent).isTrue();
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("发送保活注释帧")
    void testSendComment() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);

        boolean sent = SseStreamHelper.sendComment(emitter, "keepalive");
        assertThat(sent).isTrue();
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("优雅结束与带异常结束")
    void testComplete() {
        SseEmitter emitter = mock(SseEmitter.class);

        assertThatCode(() -> SseStreamHelper.complete(emitter)).doesNotThrowAnyException();
        verify(emitter, times(1)).complete();

        SseEmitter emitter2 = mock(SseEmitter.class);
        RuntimeException ex = new RuntimeException("custom error");
        assertThatCode(() -> SseStreamHelper.completeWithError(emitter2, ex)).doesNotThrowAnyException();
        verify(emitter2, times(1)).completeWithError(ex);
    }

    @Test
    @DisplayName("客户端中断断开（Broken pipe）精准识别与静默捕获，杜绝日志风暴")
    void testClientAbortQuietSuppression() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        IOException brokenPipe = new IOException("Broken pipe");
        doThrow(brokenPipe).when(emitter).send(anyString());

        // 当底层网络写中断时，sendChunk 应该返回 false，并且静默捕获异常、自动触发 complete()
        boolean result = SseStreamHelper.sendChunk(emitter, "token-chunk");
        assertThat(result).isFalse();
        verify(emitter, times(1)).complete();

        // 验证 completeWithError 传入 Broken pipe 时降级为静默 complete()
        SseEmitter emitter2 = mock(SseEmitter.class);
        SseStreamHelper.completeWithError(emitter2, new IOException("Connection reset by peer"));
        verify(emitter2, times(1)).complete();
        verify(emitter2, never()).completeWithError(any());
    }

    @Test
    @DisplayName("客户端中断断开异常检测判定规则")
    void testIsClientAbortException() {
        assertThat(SseStreamHelper.isClientAbortException(null)).isFalse();
        assertThat(SseStreamHelper.isClientAbortException(new IllegalArgumentException("invalid"))).isFalse();

        // 识别各类底层常见中断异常信息
        assertThat(SseStreamHelper.isClientAbortException(new IOException("Broken pipe"))).isTrue();
        assertThat(SseStreamHelper.isClientAbortException(new IOException("Connection reset"))).isTrue();
        assertThat(SseStreamHelper.isClientAbortException(new IOException("An established connection was aborted"))).isTrue();
        assertThat(SseStreamHelper.isClientAbortException(new IOException("Stream closed"))).isTrue();
        assertThat(SseStreamHelper.isClientAbortException(new IllegalStateException("ResponseBodyEmitter has already completed"))).isTrue();
        assertThat(SseStreamHelper.isClientAbortException(new IOException("远程主机强迫关闭了一个现有的连接"))).isTrue();

        // 嵌套原因链测试
        Exception wrapped = new RuntimeException("wrap", new IOException("broken pipe"));
        assertThat(SseStreamHelper.isClientAbortException(wrapped)).isTrue();
    }
}
