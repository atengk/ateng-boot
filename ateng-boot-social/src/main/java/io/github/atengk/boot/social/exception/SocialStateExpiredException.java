package io.github.atengk.boot.social.exception;

import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.social.code.SocialResultCode;

/**
 * OAuth2 防伪状态 State 非法或过期受控业务异常
 * <p>
 * 当回调中携带的 state 为空、已过期、在服务端缓存中未找到或已被并发重复消费时抛出。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class SocialStateExpiredException extends SocialException {

    private static final long serialVersionUID = 1L;

    public SocialStateExpiredException(String state) {
        super(SocialResultCode.SOCIAL_STATE_INVALID,
                "OAuth2 状态码 State 非法、已过期或已被重复消费: [" + state + "]");
    }

    public SocialStateExpiredException(String message, Throwable cause) {
        super(SocialResultCode.SOCIAL_STATE_INVALID, message, cause);
    }

    public SocialStateExpiredException(IResultCode resultCode, String message) {
        super(resultCode, message);
    }

    public SocialStateExpiredException(IResultCode resultCode, String message, Throwable cause) {
        super(resultCode, message, cause);
    }
}
