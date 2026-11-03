package io.github.atengk.boot.ai.exception;

import dev.ai4j.openai4j.OpenAiHttpException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * 大模型通信异常翻译与重试断言器
 *
 * @author Ateng
 * @since 2026-11-03
 */
public final class AiExceptionTranslator {

    private AiExceptionTranslator() {
    }

    /**
     * 判断异常是否属于瞬态可重试异常 (429 限流、5xx 服务端错误、网络连接/读取超时)
     *
     * @param t 捕获的异常对象
     * @return 可重试返回 true，否则返回 false
     */
    public static boolean isRetryable(Throwable t) {
        if (t == null) {
            return false;
        }
        Throwable current = t;
        while (current != null) {
            if (current instanceof OpenAiHttpException httpEx) {
                int code = httpEx.code();
                return code == 429 || code >= 500;
            }
            if (current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof TimeoutException) {
                return true;
            }
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("429") || lower.contains("rate limit") || lower.contains("timeout") || lower.contains("connection reset")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 将底层 SDK 或通信异常转译为语义化业务异常 AiException 并完整保留因果链
     *
     * @param t 原始异常
     * @return 框架受管 AiException
     */
    public static AiException translate(Throwable t) {
        if (t instanceof AiException aiEx) {
            return aiEx;
        }

        Throwable root = findRootCause(t);

        if (root instanceof OpenAiHttpException httpEx) {
            int code = httpEx.code();
            if (code == 401 || code == 403) {
                return new AiException(AiResultCode.AI_AUTH_FAILED, "大模型认证鉴权失败 (HTTP " + code + "): " + httpEx.getMessage(), t);
            }
            if (code == 429) {
                return new AiException(AiResultCode.AI_RATE_LIMIT_EXCEEDED, "大模型调用触发限流 (HTTP 429): " + httpEx.getMessage(), t);
            }
            if (code == 400 || code == 422) {
                return new AiException(AiResultCode.AI_INVALID_PAYLOAD, "大模型入参载荷非法 (HTTP " + code + "): " + httpEx.getMessage(), t);
            }
        }

        if (root instanceof SocketTimeoutException || root instanceof TimeoutException) {
            return new AiException(AiResultCode.AI_TIMEOUT, "大模型网络连接或读取响应超时: " + root.getMessage(), t);
        }

        String msg = t.getMessage() != null ? t.getMessage() : "大模型调用未决异常";
        String lower = msg.toLowerCase();
        if (lower.contains("429") || lower.contains("rate limit")) {
            return new AiException(AiResultCode.AI_RATE_LIMIT_EXCEEDED, msg, t);
        }
        if (lower.contains("401") || lower.contains("403") || lower.contains("unauthorized")) {
            return new AiException(AiResultCode.AI_AUTH_FAILED, msg, t);
        }

        return new AiException(AiResultCode.AI_CALL_FAILED, msg, t);
    }

    private static Throwable findRootCause(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }
}
