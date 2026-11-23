package io.github.atengk.boot.ai;

import dev.ai4j.openai4j.OpenAiHttpException;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiExceptionTranslator;
import io.github.atengk.boot.ai.exception.AiResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 通信异常翻译与重试断言器单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class AiExceptionTranslatorTest {

    @Test
    @DisplayName("验证可重试异常判定 (429, 5xx, 超时, 连接重置)")
    void testIsRetryable() {
        assertThat(AiExceptionTranslator.isRetryable(new OpenAiHttpException(429, "Rate limit reached"))).isTrue();
        assertThat(AiExceptionTranslator.isRetryable(new OpenAiHttpException(500, "Internal Server Error"))).isTrue();
        assertThat(AiExceptionTranslator.isRetryable(new OpenAiHttpException(503, "Service Unavailable"))).isTrue();
        assertThat(AiExceptionTranslator.isRetryable(new SocketTimeoutException("Read timed out"))).isTrue();
        assertThat(AiExceptionTranslator.isRetryable(new ConnectException("Connection refused"))).isTrue();
        assertThat(AiExceptionTranslator.isRetryable(new TimeoutException("Timeout"))).isTrue();

        // 嵌套异常
        RuntimeException wrapped = new RuntimeException("Outer", new SocketTimeoutException("Inner timeout"));
        assertThat(AiExceptionTranslator.isRetryable(wrapped)).isTrue();
    }

    @Test
    @DisplayName("验证不可重试异常判定 (401, 403, 400)")
    void testNonRetryable() {
        assertThat(AiExceptionTranslator.isRetryable(new OpenAiHttpException(401, "Unauthorized"))).isFalse();
        assertThat(AiExceptionTranslator.isRetryable(new OpenAiHttpException(403, "Forbidden"))).isFalse();
        assertThat(AiExceptionTranslator.isRetryable(new OpenAiHttpException(400, "Bad Request"))).isFalse();
        assertThat(AiExceptionTranslator.isRetryable(new IllegalArgumentException("Invalid argument"))).isFalse();
        assertThat(AiExceptionTranslator.isRetryable(null)).isFalse();
    }

    @Test
    @DisplayName("转译 401/403 为 AI_AUTH_FAILED 异常并保留因果链")
    void testTranslateAuthFailed() {
        OpenAiHttpException ex = new OpenAiHttpException(401, "Incorrect API key provided");
        AiException aiException = AiExceptionTranslator.translate(ex);

        assertThat(aiException.getResultCode()).isEqualTo(AiResultCode.AI_AUTH_FAILED);
        assertThat(aiException.getCause()).isSameAs(ex);
    }

    @Test
    @DisplayName("转译 429 为 AI_RATE_LIMIT_EXCEEDED 异常")
    void testTranslateRateLimit() {
        OpenAiHttpException ex = new OpenAiHttpException(429, "Rate limit exceeded");
        AiException aiException = AiExceptionTranslator.translate(ex);

        assertThat(aiException.getResultCode()).isEqualTo(AiResultCode.AI_RATE_LIMIT_EXCEEDED);
        assertThat(aiException.getCause()).isSameAs(ex);
    }

    @Test
    @DisplayName("转译超时为 AI_TIMEOUT 异常")
    void testTranslateTimeout() {
        SocketTimeoutException ex = new SocketTimeoutException("Read timed out");
        AiException aiException = AiExceptionTranslator.translate(ex);

        assertThat(aiException.getResultCode()).isEqualTo(AiResultCode.AI_TIMEOUT);
        assertThat(aiException.getCause()).isSameAs(ex);
    }

    @Test
    @DisplayName("转译 400/422 为 AI_INVALID_PAYLOAD 异常")
    void testTranslateInvalidPayload() {
        OpenAiHttpException ex = new OpenAiHttpException(400, "Model does not exist");
        AiException aiException = AiExceptionTranslator.translate(ex);

        assertThat(aiException.getResultCode()).isEqualTo(AiResultCode.AI_INVALID_PAYLOAD);
        assertThat(aiException.getCause()).isSameAs(ex);
    }

    @Test
    @DisplayName("若传入异常本身为 AiException 则直接原样返回")
    void testDirectAiExceptionReturn() {
        AiException original = new AiException(AiResultCode.AI_CONFIG_ERROR, "Already translated");
        AiException result = AiExceptionTranslator.translate(original);

        assertThat(result).isSameAs(original);
    }

    @Test
    @DisplayName("验证多层嵌套异常中穿透识别 OpenAiHttpException 并精确转译")
    void testTranslateNestedOpenAiHttpException() {
        OpenAiHttpException cause = new OpenAiHttpException(429, "Rate limit reached");
        RuntimeException intermediate = new RuntimeException("Intermediate wrapper", cause);
        RuntimeException outer = new RuntimeException("Outer wrapper", intermediate);

        AiException aiException = AiExceptionTranslator.translate(outer);
        assertThat(aiException.getResultCode()).isEqualTo(AiResultCode.AI_RATE_LIMIT_EXCEEDED);
        assertThat(aiException.getCause()).isSameAs(outer);
    }
}
