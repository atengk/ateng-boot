package io.github.atengk.boot.http.error;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.code.IResultCode;
import lombok.Getter;

/**
 * HTTP 远程通信专职语义化结果码枚举契约
 * 统一实现 Core 模块 IResultCode 契约，赋予以 4/5 开头的清晰业务状态码，
 * 方便调用方及可观测性看板一目了然区分本地应用错误与远端下游错误
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Getter
public enum HttpResultCode implements IResultCode {

    /**
     * 远程下游客户端请求错误 (40001)
     */
    REMOTE_HTTP_CLIENT_ERROR(40001, "远程服务客户端请求错误"),

    /**
     * 远程下游身份未认证 (40002)
     */
    REMOTE_HTTP_UNAUTHORIZED(40002, "远程服务访问未授权或身份无效"),

    /**
     * 远程下游拒绝访问 (40003)
     */
    REMOTE_HTTP_FORBIDDEN(40003, "远程服务拒绝当前请求访问"),

    /**
     * 远程下游目标资源不存在 (40004)
     */
    REMOTE_HTTP_NOT_FOUND(40004, "远程服务目标资源不存在"),

    /**
     * 远程下游服务内部异常 (50001)
     */
    REMOTE_HTTP_SERVER_ERROR(50001, "远程服务内部发生异常"),

    /**
     * 远程下游网关或代理错误 (50002)
     */
    REMOTE_HTTP_GATEWAY_ERROR(50002, "远程网关或上游服务器响应错误"),

    /**
     * 远程下游服务暂时不可用 (50003)
     */
    REMOTE_HTTP_SERVICE_UNAVAILABLE(50003, "远程服务暂时不可用或处于维护过载状态"),

    /**
     * 远程下游调用超时 (50004)
     */
    REMOTE_HTTP_TIMEOUT(50004, "远程服务网关调用响应超时");

    private final Integer code;
    private final String msg;

    HttpResultCode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    /**
     * 根据 HTTP 状态码映射到专职语义化远程结果码
     *
     * @param httpStatusCode HTTP 响应状态码
     * @return 映射后的 IResultCode 契约对象
     */
    public static IResultCode fromHttpStatus(int httpStatusCode) {
        if (httpStatusCode >= 400 && httpStatusCode < 500) {
            return switch (httpStatusCode) {
                case 401 -> REMOTE_HTTP_UNAUTHORIZED;
                case 403 -> REMOTE_HTTP_FORBIDDEN;
                case 404 -> REMOTE_HTTP_NOT_FOUND;
                default -> REMOTE_HTTP_CLIENT_ERROR;
            };
        }
        if (httpStatusCode >= 500) {
            return switch (httpStatusCode) {
                case 502 -> REMOTE_HTTP_GATEWAY_ERROR;
                case 503 -> REMOTE_HTTP_SERVICE_UNAVAILABLE;
                case 504 -> REMOTE_HTTP_TIMEOUT;
                default -> REMOTE_HTTP_SERVER_ERROR;
            };
        }
        return CommonResultCode.INTERNAL_SERVER_ERROR;
    }
}
