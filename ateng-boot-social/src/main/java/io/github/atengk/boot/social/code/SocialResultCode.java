package io.github.atengk.boot.social.code;

import io.github.atengk.boot.core.code.IResultCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 社交登录与 OAuth2 业务错误码契约
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Getter
@AllArgsConstructor
public enum SocialResultCode implements IResultCode {

    /**
     * 第三方社交应用未配置或未找到
     */
    SOCIAL_APP_NOT_FOUND(7001, "第三方社交应用未配置或未找到"),

    /**
     * 孤儿账号解绑拦截
     */
    SOCIAL_UNBIND_REJECTED(7002, "当前账号不允许解绑此第三方登录方式（孤儿账号防卫拦截）"),

    /**
     * 第三方 OAuth2 授权认证失败
     */
    SOCIAL_AUTH_FAILED(7003, "第三方社交授权认证失败"),

    /**
     * OAuth2 状态码 State 非法或已过期
     */
    SOCIAL_STATE_INVALID(7004, "OAuth2 状态码 State 非法或已过期"),

    /**
     * 两阶段绑定票据 BindTicket 非法、不存在或已过期
     */
    SOCIAL_BIND_TICKET_INVALID(7005, "绑定票据 BindTicket 非法或已过期"),

    /**
     * 未找到第三方账号绑定的本地用户
     */
    SOCIAL_USER_NOT_FOUND(7006, "未查找到绑定的本地用户"),

    /**
     * 该第三方社交账号已被其他本地用户绑定
     */
    SOCIAL_ALREADY_BOUND(7007, "该第三方社交账号已被其他本地用户绑定"),

    /**
     * 非法的授权回调重定向地址
     */
    SOCIAL_REDIRECT_URI_INVALID(7008, "非法的授权回调重定向地址"),

    /**
     * 社交登录内部异常
     */
    SOCIAL_ERROR(7000, "第三方社交登录异常");

    private final Integer code;
    private final String msg;
}
