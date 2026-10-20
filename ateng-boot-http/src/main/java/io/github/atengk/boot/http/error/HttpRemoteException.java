package io.github.atengk.boot.http.error;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import lombok.Getter;
import org.springframework.http.HttpMethod;

import java.net.URI;

/**
 * 远程 HTTP 交互异常体系
 * 统一捕获并转译下游服务返回的 4xx/5xx 等异常状态，挂载完整的上下文与安全截断响应体
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Getter
public class HttpRemoteException extends BusinessException {

    private static final long serialVersionUID = 1L;

    /**
     * HTTP 响应状态码（如 400, 404, 500 等）
     */
    private final int httpStatusCode;

    /**
     * 发起请求的 HTTP 动词（GET / POST / PUT / DELETE 等）
     */
    private final HttpMethod requestMethod;

    /**
     * 目标请求 URI 路径
     */
    private final URI requestUrl;

    /**
     * 远程服务返回的原始响应体（已执行安全截断与二进制过滤）
     */
    private final String responseBody;

    /**
     * 基于 HTTP 基础元数据构造远程异常
     *
     * @param httpStatusCode HTTP 响应状态码
     * @param requestMethod  HTTP 请求动词
     * @param requestUrl     请求目标 URI
     * @param responseBody   截断后的响应体
     */
    public HttpRemoteException(int httpStatusCode, HttpMethod requestMethod, URI requestUrl, String responseBody) {
        this(resolveDefaultResultCode(httpStatusCode), httpStatusCode, requestMethod, requestUrl, responseBody, null);
    }

    /**
     * 基于 HTTP 基础元数据与底层根因异常构造远程异常
     *
     * @param httpStatusCode HTTP 响应状态码
     * @param requestMethod  HTTP 请求动词
     * @param requestUrl     请求目标 URI
     * @param responseBody   截断后的响应体
     * @param cause          底层根因异常
     */
    public HttpRemoteException(int httpStatusCode, HttpMethod requestMethod, URI requestUrl, String responseBody, Throwable cause) {
        this(resolveDefaultResultCode(httpStatusCode), httpStatusCode, requestMethod, requestUrl, responseBody, cause);
    }

    /**
     * 基于自定义错误码契约、HTTP 基础元数据与根因异常构造远程异常
     *
     * @param resultCode     自定义响应状态码契约
     * @param httpStatusCode HTTP 响应状态码
     * @param requestMethod  HTTP 请求动词
     * @param requestUrl     请求目标 URI
     * @param responseBody   截断后的响应体
     * @param cause          底层根因异常
     */
    public HttpRemoteException(IResultCode resultCode, int httpStatusCode, HttpMethod requestMethod, URI requestUrl, String responseBody, Throwable cause) {
        super(resultCode != null ? resultCode : resolveDefaultResultCode(httpStatusCode),
                formatMessage(httpStatusCode, requestMethod, requestUrl, responseBody),
                cause);
        this.httpStatusCode = httpStatusCode;
        this.requestMethod = requestMethod;
        this.requestUrl = requestUrl;
        this.responseBody = responseBody;
    }

    /**
     * 依据 HTTP 状态码映射到通用的系统响应契约
     *
     * @param httpStatusCode HTTP 状态码
     * @return 映射后的错误码契约
     */
    private static IResultCode resolveDefaultResultCode(int httpStatusCode) {
        return switch (httpStatusCode) {
            case 400 -> CommonResultCode.BAD_REQUEST;
            case 401 -> CommonResultCode.UNAUTHORIZED;
            case 403 -> CommonResultCode.FORBIDDEN;
            case 404 -> CommonResultCode.NOT_FOUND;
            case 405 -> CommonResultCode.METHOD_NOT_ALLOWED;
            case 429 -> CommonResultCode.TOO_MANY_REQUESTS;
            default -> CommonResultCode.INTERNAL_SERVER_ERROR;
        };
    }

    /**
     * 格式化生成异常详细摘要
     *
     * @param httpStatusCode HTTP 状态码
     * @param requestMethod  HTTP 请求动词
     * @param requestUrl     请求目标 URI
     * @param responseBody   响应体
     * @return 格式化后的错误提示
     */
    private static String formatMessage(int httpStatusCode, HttpMethod requestMethod, URI requestUrl, String responseBody) {
        StringBuilder sb = new StringBuilder("HTTP ").append(httpStatusCode);
        if (requestMethod != null) {
            sb.append(" ").append(requestMethod.name());
        }
        if (requestUrl != null) {
            sb.append(" [").append(requestUrl).append("]");
        }
        if (responseBody != null && !responseBody.isBlank()) {
            sb.append(" - ").append(responseBody);
        }
        return sb.toString();
    }
}
