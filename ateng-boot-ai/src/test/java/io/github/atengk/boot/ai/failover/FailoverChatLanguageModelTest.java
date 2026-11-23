package io.github.atengk.boot.ai.failover;

import dev.ai4j.openai4j.OpenAiHttpException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 声明式多模型故障自动转移同步聊天模型单元测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
class FailoverChatLanguageModelTest {

    @Test
    @DisplayName("主模型调用成功时，备用模型不应被调用")
    void testPrimarySuccessWithoutFailover() {
        ChatLanguageModel primary = mock(ChatLanguageModel.class);
        ChatLanguageModel fallback = mock(ChatLanguageModel.class);

        when(primary.generate(anyList())).thenReturn(Response.from(AiMessage.from("Primary Reply")));

        FailoverChatLanguageModel failoverModel = FailoverChatLanguageModel.builder()
                .primary("primary-model", primary)
                .fallback("fallback-model", fallback)
                .totalTimeout(Duration.ofSeconds(10))
                .build();

        Response<AiMessage> response = failoverModel.generate(List.of(UserMessage.from("Hello")));

        assertThat(response.content().text()).isEqualTo("Primary Reply");
        verify(primary, times(1)).generate(anyList());
        verify(fallback, never()).generate(anyList());
    }

    @Test
    @DisplayName("主模型遭遇 HTTP 503 时，自动故障转移至备选模型")
    void testFailoverOnHttp503() {
        ChatLanguageModel primary = mock(ChatLanguageModel.class);
        ChatLanguageModel fallback = mock(ChatLanguageModel.class);

        when(primary.generate(anyList())).thenThrow(new OpenAiHttpException(503, "Service Unavailable"));
        when(fallback.generate(anyList())).thenReturn(Response.from(AiMessage.from("Fallback Reply")));

        FailoverChatLanguageModel failoverModel = FailoverChatLanguageModel.builder()
                .primary("primary-model", primary)
                .fallback("fallback-model", fallback)
                .totalTimeout(Duration.ofSeconds(10))
                .build();

        Response<AiMessage> response = failoverModel.generate(List.of(UserMessage.from("Hello")));

        assertThat(response.content().text()).isEqualTo("Fallback Reply");
        verify(primary, times(1)).generate(anyList());
        verify(fallback, times(1)).generate(anyList());
    }

    @Test
    @DisplayName("主备多级级联故障转移：Primary 503 -> Backup1 504 -> Backup2 成功")
    void testMultiTierCascadingFailover() {
        ChatLanguageModel primary = mock(ChatLanguageModel.class);
        ChatLanguageModel backup1 = mock(ChatLanguageModel.class);
        ChatLanguageModel backup2 = mock(ChatLanguageModel.class);

        when(primary.generate(anyList())).thenThrow(new OpenAiHttpException(503, "Service Unavailable"));
        when(backup1.generate(anyList())).thenThrow(new OpenAiHttpException(504, "Gateway Timeout"));
        when(backup2.generate(anyList())).thenReturn(Response.from(AiMessage.from("Backup2 Reply")));

        FailoverChatLanguageModel failoverModel = FailoverChatLanguageModel.builder()
                .primary("primary", primary)
                .fallback("backup1", backup1)
                .fallback("backup2", backup2)
                .build();

        Response<AiMessage> response = failoverModel.generate(List.of(UserMessage.from("Hello")));

        assertThat(response.content().text()).isEqualTo("Backup2 Reply");
        verify(primary, times(1)).generate(anyList());
        verify(backup1, times(1)).generate(anyList());
        verify(backup2, times(1)).generate(anyList());
    }

    @Test
    @DisplayName("非瞬态错误 (HTTP 400 Bad Request) 触发 Fail-Fast，坚决不进行故障转移")
    void testFailFastOnHttp400() {
        ChatLanguageModel primary = mock(ChatLanguageModel.class);
        ChatLanguageModel fallback = mock(ChatLanguageModel.class);

        when(primary.generate(anyList())).thenThrow(new OpenAiHttpException(400, "Bad Request: prompt tokens exceed limit"));

        FailoverChatLanguageModel failoverModel = FailoverChatLanguageModel.builder()
                .primary("primary-model", primary)
                .fallback("fallback-model", fallback)
                .build();

        List<ChatMessage> messages = List.of(UserMessage.from("Too long prompt"));
        assertThatThrownBy(() -> failoverModel.generate(messages))
                .isInstanceOf(AiException.class);

        verify(primary, times(1)).generate(anyList());
        verify(fallback, never()).generate(anyList());
    }

    @Test
    @DisplayName("非网关内部异常 (HTTP 500) 不属于故障转移白名单，触发 Fail-Fast 避免扩散雪崩")
    void testFailFastOnHttp500() {
        ChatLanguageModel primary = mock(ChatLanguageModel.class);
        ChatLanguageModel fallback = mock(ChatLanguageModel.class);

        when(primary.generate(anyList())).thenThrow(new OpenAiHttpException(500, "Internal Server Error"));

        FailoverChatLanguageModel failoverModel = FailoverChatLanguageModel.builder()
                .primary("primary-model", primary)
                .fallback("fallback-model", fallback)
                .build();

        List<ChatMessage> messages = List.of(UserMessage.from("Hello"));
        assertThatThrownBy(() -> failoverModel.generate(messages))
                .isInstanceOf(AiException.class);

        verify(primary, times(1)).generate(anyList());
        verify(fallback, never()).generate(anyList());
    }

    @Test
    @DisplayName("全局时延预算 (Total Deadline Budget) 耗尽时，终止后续故障转移并抛出超时异常")
    void testTotalDeadlineExhausted() {
        ChatLanguageModel primary = mock(ChatLanguageModel.class);
        ChatLanguageModel fallback = mock(ChatLanguageModel.class);

        // 主模型模拟耗时超过 totalTimeout
        when(primary.generate(anyList())).thenAnswer(invocation -> {
            Thread.sleep(60);
            throw new OpenAiHttpException(503, "Service Unavailable");
        });

        // 设置极短的全局时延预算 20ms
        FailoverChatLanguageModel failoverModel = FailoverChatLanguageModel.builder()
                .primary("primary-model", primary)
                .fallback("fallback-model", fallback)
                .totalTimeout(Duration.ofMillis(20))
                .build();

        List<ChatMessage> messages = List.of(UserMessage.from("Hello"));
        assertThatThrownBy(() -> failoverModel.generate(messages))
                .isInstanceOf(AiException.class)
                .satisfies(ex -> {
                    AiException aiEx = (AiException) ex;
                    assertThat(aiEx.getCode()).isEqualTo(AiResultCode.AI_TIMEOUT.getCode());
                });

        verify(primary, times(1)).generate(anyList());
        verify(fallback, never()).generate(anyList());
    }

    @Test
    @DisplayName("所有候选模型全数失败时，抛出最终转译业务异常并完整保留因果链")
    void testAllCandidatesFailedThrowsException() {
        ChatLanguageModel primary = mock(ChatLanguageModel.class);
        ChatLanguageModel fallback = mock(ChatLanguageModel.class);

        when(primary.generate(anyList())).thenThrow(new OpenAiHttpException(503, "Service Unavailable 1"));
        when(fallback.generate(anyList())).thenThrow(new OpenAiHttpException(503, "Service Unavailable 2"));

        FailoverChatLanguageModel failoverModel = FailoverChatLanguageModel.builder()
                .primary("primary", primary)
                .fallback("fallback", fallback)
                .build();

        List<ChatMessage> messages = List.of(UserMessage.from("Hello"));
        assertThatThrownBy(() -> failoverModel.generate(messages))
                .isInstanceOf(AiException.class);

        verify(primary, times(1)).generate(anyList());
        verify(fallback, times(1)).generate(anyList());
    }
}
