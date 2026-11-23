package io.github.atengk.boot.ai.failover;

import dev.ai4j.openai4j.OpenAiHttpException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 声明式多模型故障自动转移异步流式聊天模型单元测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
class FailoverStreamingChatLanguageModelTest {

    @Test
    @DisplayName("主流式模型未输出 Token 前发生 503 异常，成功故障转移至备选模型")
    void testStreamingFailoverBeforeCommitted() {
        StreamingChatLanguageModel primary = mock(StreamingChatLanguageModel.class);
        StreamingChatLanguageModel fallback = mock(StreamingChatLanguageModel.class);

        // primary 在未输出 token 前即调用 onError(503)
        doAnswer(invocation -> {
            StreamingResponseHandler<AiMessage> handler = invocation.getArgument(1);
            handler.onError(new OpenAiHttpException(503, "Service Unavailable"));
            return null;
        }).when(primary).generate(anyList(), any());

        // fallback 成功输出 tokens
        doAnswer(invocation -> {
            StreamingResponseHandler<AiMessage> handler = invocation.getArgument(1);
            handler.onNext("Hello ");
            handler.onNext("World");
            handler.onComplete(Response.from(AiMessage.from("Hello World")));
            return null;
        }).when(fallback).generate(anyList(), any());

        FailoverStreamingChatLanguageModel failoverModel = FailoverStreamingChatLanguageModel.builder()
                .primary("primary", primary)
                .fallback("fallback", fallback)
                .totalTimeout(Duration.ofSeconds(10))
                .build();

        List<String> receivedTokens = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        failoverModel.generate(List.of(UserMessage.from("Hi")), new StreamingResponseHandler<>() {
            @Override
            public void onNext(String token) {
                receivedTokens.add(token);
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                completed.set(true);
            }

            @Override
            public void onError(Throwable error) {
                errorRef.set(error);
            }
        });

        assertThat(completed.get()).isTrue();
        assertThat(errorRef.get()).isNull();
        assertThat(receivedTokens).containsExactly("Hello ", "World");

        verify(primary, times(1)).generate(anyList(), any());
        verify(fallback, times(1)).generate(anyList(), any());
    }

    @Test
    @DisplayName("流式提交铁律：一旦输出过 Token，后续发生异常坚决不重试转移，直接通知下游")
    void testNoFailoverAfterStreamCommitted() {
        StreamingChatLanguageModel primary = mock(StreamingChatLanguageModel.class);
        StreamingChatLanguageModel fallback = mock(StreamingChatLanguageModel.class);

        // primary 输出了一个 Token，然后遭遇 503 异常
        doAnswer(invocation -> {
            StreamingResponseHandler<AiMessage> handler = invocation.getArgument(1);
            handler.onNext("Part 1: OK");
            handler.onError(new OpenAiHttpException(503, "Network Drop Midway"));
            return null;
        }).when(primary).generate(anyList(), any());

        FailoverStreamingChatLanguageModel failoverModel = FailoverStreamingChatLanguageModel.builder()
                .primary("primary", primary)
                .fallback("fallback", fallback)
                .build();

        List<String> receivedTokens = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        failoverModel.generate(List.of(UserMessage.from("Hi")), new StreamingResponseHandler<>() {
            @Override
            public void onNext(String token) {
                receivedTokens.add(token);
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
            }

            @Override
            public void onError(Throwable error) {
                errorRef.set(error);
            }
        });

        assertThat(receivedTokens).containsExactly("Part 1: OK");
        assertThat(errorRef.get()).isNotNull();
        assertThat(errorRef.get().getMessage()).contains("Network Drop Midway");

        // 备选模型绝对不能被调用（防止向客户端发送重复内容或状态混乱）
        verify(primary, times(1)).generate(anyList(), any());
        verify(fallback, never()).generate(anyList(), any());
    }

    @Test
    @DisplayName("非瞬态错误 (HTTP 400 Bad Request) 在流式场景立即 Fail-Fast，不触发转移")
    void testStreamingFailFastOnHttp400() {
        StreamingChatLanguageModel primary = mock(StreamingChatLanguageModel.class);
        StreamingChatLanguageModel fallback = mock(StreamingChatLanguageModel.class);

        doAnswer(invocation -> {
            StreamingResponseHandler<AiMessage> handler = invocation.getArgument(1);
            handler.onError(new OpenAiHttpException(400, "Bad Request: context_length_exceeded"));
            return null;
        }).when(primary).generate(anyList(), any());

        FailoverStreamingChatLanguageModel failoverModel = FailoverStreamingChatLanguageModel.builder()
                .primary("primary", primary)
                .fallback("fallback", fallback)
                .build();

        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        failoverModel.generate(List.of(UserMessage.from("Hi")), new StreamingResponseHandler<>() {
            @Override
            public void onNext(String token) {
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
            }

            @Override
            public void onError(Throwable error) {
                errorRef.set(error);
            }
        });

        assertThat(errorRef.get()).isNotNull();
        verify(primary, times(1)).generate(anyList(), any());
        verify(fallback, never()).generate(anyList(), any());
    }

    @Test
    @DisplayName("流式全局时延预算耗尽时，直接向客户端通知 AI_TIMEOUT 异常")
    void testStreamingTotalDeadlineExhausted() {
        StreamingChatLanguageModel primary = mock(StreamingChatLanguageModel.class);
        StreamingChatLanguageModel fallback = mock(StreamingChatLanguageModel.class);

        // 主模型模拟耗时超过 totalTimeout
        doAnswer(invocation -> {
            Thread.sleep(60);
            StreamingResponseHandler<AiMessage> handler = invocation.getArgument(1);
            handler.onError(new OpenAiHttpException(503, "Service Unavailable"));
            return null;
        }).when(primary).generate(anyList(), any());

        FailoverStreamingChatLanguageModel failoverModel = FailoverStreamingChatLanguageModel.builder()
                .primary("primary", primary)
                .fallback("fallback", fallback)
                .totalTimeout(Duration.ofMillis(20))
                .build();

        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        failoverModel.generate(List.of(UserMessage.from("Hi")), new StreamingResponseHandler<>() {
            @Override
            public void onNext(String token) {
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
            }

            @Override
            public void onError(Throwable error) {
                errorRef.set(error);
            }
        });

        assertThat(errorRef.get()).isInstanceOf(AiException.class);
        AiException aiEx = (AiException) errorRef.get();
        assertThat(aiEx.getCode()).isEqualTo(AiResultCode.AI_TIMEOUT.getCode());

        verify(primary, times(1)).generate(anyList(), any());
        verify(fallback, never()).generate(anyList(), any());
    }
}
