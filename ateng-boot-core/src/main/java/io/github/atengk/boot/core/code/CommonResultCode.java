package io.github.atengk.boot.core.code;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 基础通用响应状态码枚举
 *
 * @author Ateng
 * @since 2026-10-15
 */
@Getter
@AllArgsConstructor
public enum CommonResultCode implements IResultCode {

    /**
     * 操作成功
     */
    SUCCESS(200, "操作成功"),

    /**
     * 参数校验失败
     */
    BAD_REQUEST(400, "参数校验失败"),

    /**
     * 尚未登录或令牌已过期
     */
    UNAUTHORIZED(401, "尚未登录或令牌已过期"),

    /**
     * 权限不足，拒绝访问
     */
    FORBIDDEN(403, "权限不足，拒绝访问"),

    /**
     * 请求资源不存在
     */
    NOT_FOUND(404, "请求资源不存在"),

    /**
     * 请求方法不支持
     */
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),

    /**
     * 不接受的媒体类型
     */
    NOT_ACCEPTABLE(406, "不接受的媒体类型"),

    /**
     * 上传文件大小超出限制
     */
    PAYLOAD_TOO_LARGE(413, "上传文件大小超出限制"),

    /**
     * 不支持的媒体类型
     */
    UNSUPPORTED_MEDIA_TYPE(415, "不支持的媒体类型"),

    /**
     * 访问过于频繁，请稍后再试（限流拦截）
     */
    TOO_MANY_REQUESTS(429, "访问过于频繁，请稍后再试"),

    /**
     * 系统繁忙，请稍后再试
     */
    INTERNAL_SERVER_ERROR(500, "系统繁忙，请稍后再试"),

    /**
     * 服务暂不可用或处理超时
     */
    SERVICE_UNAVAILABLE(503, "服务暂不可用或处理超时"),

    /**
     * 重复提交或操作过于频繁
     */
    REPEAT_SUBMIT(40003, "操作过于频繁，请稍后再试");

    /**
     * 状态码数值
     */
    private final Integer code;

    /**
     * 状态提示文本
     */
    private final String msg;
}
