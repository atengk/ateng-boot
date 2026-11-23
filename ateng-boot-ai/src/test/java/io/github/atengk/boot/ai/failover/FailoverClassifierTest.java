package io.github.atengk.boot.ai.failover;

import dev.ai4j.openai4j.OpenAiHttpException;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 故障自动转移错误白名单分类器单元测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
class FailoverClassifierTest {

    @Test
    @DisplayName("HTTP 502/503/504 瞬态错误属于允许故障转移白名单，500 内部服务错误不属于白名单")
    void testFailoverOnHttpTransientErrors() {
        assertThat(FailoverClassifier.isFailoverable(new OpenAiHttpException(502, "Bad Gateway"))).isTrue();
        assertThat(FailoverClassifier.isFailoverable(new OpenAiHttpException(503, "Service Unavailable"))).isTrue();
        assertThat(FailoverClassifier.isFailoverable(new OpenAiHttpException(504, "Gateway Timeout"))).isTrue();
        // 500 代表服务端非网关内部异常，不属于网络瞬态错误，不进行故障转移
        assertThat(FailoverClassifier.isFailoverable(new OpenAiHttpException(500, "Internal Server Error"))).isFalse();
    }

    @Test
    @DisplayName("网络掉线、连接超时与读写超时属于允许故障转移白名单")
    void testFailoverOnNetworkAndTimeoutErrors() {
        assertThat(FailoverClassifier.isFailoverable(new ConnectException("Connection refused: connect"))).isTrue();
        assertThat(FailoverClassifier.isFailoverable(new SocketTimeoutException("Read timed out"))).isTrue();
        assertThat(FailoverClassifier.isFailoverable(new TimeoutException("Operation timed out"))).isTrue();
        assertThat(FailoverClassifier.isFailoverable(new UnknownHostException("api.deepseek.com"))).isTrue();
        assertThat(FailoverClassifier.isFailoverable(new RuntimeException("Connection reset by peer"))).isTrue();
    }

    @Test
    @DisplayName("AiException 中的 AI_TIMEOUT 属于白名单")
    void testFailoverOnAiTimeoutCode() {
        AiException timeoutEx = new AiException(AiResultCode.AI_TIMEOUT, "网络通信超时");
        assertThat(FailoverClassifier.isFailoverable(timeoutEx)).isTrue();
    }

    @Test
    @DisplayName("HTTP 400 Bad Request、401 未授权、403 禁止访问、404 未找到属于快速失败非白名单")
    void testFailFastOnHttp4xxErrors() {
        assertThat(FailoverClassifier.isFailoverable(new OpenAiHttpException(400, "Bad Request: context_length_exceeded"))).isFalse();
        assertThat(FailoverClassifier.isFailoverable(new OpenAiHttpException(401, "Invalid API Key"))).isFalse();
        assertThat(FailoverClassifier.isFailoverable(new OpenAiHttpException(403, "Forbidden"))).isFalse();
        assertThat(FailoverClassifier.isFailoverable(new OpenAiHttpException(404, "Model not found"))).isFalse();
    }

    @Test
    @DisplayName("参数非法、入参格式错误、内容涉黄涉暴属于快速失败非白名单")
    void testFailFastOnClientErrors() {
        assertThat(FailoverClassifier.isFailoverable(new IllegalArgumentException("Prompt cannot be empty"))).isFalse();
        assertThat(FailoverClassifier.isFailoverable(new AiException(AiResultCode.AI_INVALID_PAYLOAD, "请求参数非法"))).isFalse();
        assertThat(FailoverClassifier.isFailoverable(new AiException(AiResultCode.AI_AUTH_FAILED, "鉴权失败"))).isFalse();
        assertThat(FailoverClassifier.isFailoverable(new AiException(AiResultCode.AI_CONTENT_RISK_DETECTED, "触发敏感词"))).isFalse();
        assertThat(FailoverClassifier.isFailoverable(new RuntimeException("maximum context length is 4096 tokens"))).isFalse();
    }

    @Test
    @DisplayName("空入参防御测试")
    void testNullSafe() {
        assertThat(FailoverClassifier.isFailoverable(null)).isFalse();
    }
}
