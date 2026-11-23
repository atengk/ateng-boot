package io.github.atengk.boot.ai.failover;

import dev.ai4j.openai4j.OpenAiHttpException;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;

import java.io.EOFException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;

/**
 * 模型故障自动转移错误白名单分类器
 * <p>
 * 遵循 SRE 生产防雪崩铁律：
 * <ul>
 *   <li><b>白名单故障转移 (Failover)</b>：仅针对底层网络通信失败、连接中断、读写超时及上游 502/503/504 等瞬态网关/服务故障触发转移；</li>
 *   <li><b>非瞬态快速失败 (Fail-Fast)</b>：对客户端 4xx 错误（如 400 Bad Request、401/403 鉴权失败、上下文超长、提示词非法等）坚决快速失败，严禁盲目向后备模型重试导致扩散雪崩。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public final class FailoverClassifier {

    private FailoverClassifier() {
    }

    /**
     * 判断异常是否属于允许故障转移的瞬态错误白名单
     *
     * @param t 捕获的异常对象
     * @return 若符合白名单返回 true，否则返回 false (快速失败)
     */
    public static boolean isFailoverable(Throwable t) {
        if (t == null) {
            return false;
        }

        // 1. 前置快速失败防卫：显式 4xx 错误或非瞬态业务异常直接排除
        if (isNonTransientClientError(t)) {
            return false;
        }

        // 2. 白名单判定：沿因果链逐层检查
        Throwable current = t;
        while (current != null) {
            // (1) OpenAI / HTTP 状态码白名单检查
            if (current instanceof OpenAiHttpException httpEx) {
                int code = httpEx.code();
                if (code == 502 || code == 503 || code == 504) {
                    return true;
                }
                if (code >= 400 && code < 500) {
                    return false;
                }
            }

            // (2) 框架已转译的 AiException 状态码检查
            if (current instanceof AiException aiEx) {
                if (aiEx.getCode() == AiResultCode.AI_TIMEOUT.getCode()) {
                    return true;
                }
                if (aiEx.getCode() == AiResultCode.AI_INVALID_PAYLOAD.getCode()
                        || aiEx.getCode() == AiResultCode.AI_AUTH_FAILED.getCode()
                        || aiEx.getCode() == AiResultCode.AI_CONFIG_ERROR.getCode()) {
                    return false;
                }
            }

            // (3) 网络与通信中断异常
            if (current instanceof ConnectException
                    || current instanceof SocketTimeoutException
                    || current instanceof TimeoutException
                    || current instanceof UnknownHostException
                    || current instanceof SocketException
                    || current instanceof SSLException
                    || current instanceof EOFException
                    || current instanceof ClosedChannelException) {
                return true;
            }

            // JDK 11+ java.net.http.HttpTimeoutException 或 HttpConnectTimeoutException
            String className = current.getClass().getName();
            if (className.contains("HttpTimeoutException") || className.contains("HttpConnectTimeoutException")) {
                return true;
            }

            // (4) 异常消息关键词判定
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("502") || lower.contains("503") || lower.contains("504")
                        || lower.contains("bad gateway")
                        || lower.contains("service unavailable")
                        || lower.contains("gateway timeout")
                        || lower.contains("connection reset")
                        || lower.contains("connection refused")
                        || lower.contains("broken pipe")
                        || lower.contains("unexpected end of stream")
                        || lower.contains("timed out")
                        || lower.contains("timeout")) {
                    return true;
                }
            }

            current = current.getCause();
        }

        return false;
    }

    /**
     * 判断异常是否属于客户端确定性非法入参/鉴权等非瞬态错误 (Fail-Fast)
     */
    private static boolean isNonTransientClientError(Throwable t) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof IllegalArgumentException) {
                return true;
            }
            if (current instanceof OpenAiHttpException httpEx) {
                int code = httpEx.code();
                if (code >= 400 && code < 500) {
                    return true;
                }
            }
            if (current instanceof AiException aiEx) {
                int code = aiEx.getCode();
                if (code == AiResultCode.AI_INVALID_PAYLOAD.getCode()
                        || code == AiResultCode.AI_AUTH_FAILED.getCode()
                        || code == AiResultCode.AI_CONFIG_ERROR.getCode()
                        || code == AiResultCode.AI_CONTENT_RISK_DETECTED.getCode()) {
                    return true;
                }
            }
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("context_length_exceeded")
                        || lower.contains("maximum context length")
                        || lower.contains("invalid_request_error")
                        || lower.contains("bad request")
                        || lower.contains("unauthorized")
                        || lower.contains("invalid api key")
                        || lower.contains("model_not_found")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
