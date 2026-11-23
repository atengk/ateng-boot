package io.github.atengk.boot.social.exception;

import io.github.atengk.boot.social.code.SocialResultCode;

/**
 * 孤儿账号解绑拦截受控业务异常
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class SocialUnbindRejectedException extends SocialException {

    private static final long serialVersionUID = 1L;

    public SocialUnbindRejectedException(String userId, String appKey) {
        super(SocialResultCode.SOCIAL_UNBIND_REJECTED,
                "本地用户 [" + userId + "] 不允许解绑第三方应用 [" + appKey + "]：未绑定手机号或未设独立密码，防止账号孤儿化");
    }

    public SocialUnbindRejectedException(String userId, String appKey, Throwable cause) {
        super(SocialResultCode.SOCIAL_UNBIND_REJECTED,
                "本地用户 [" + userId + "] 不允许解绑第三方应用 [" + appKey + "]：未绑定手机号或未设独立密码，防止账号孤儿化", cause);
    }

    public SocialUnbindRejectedException(String message) {
        super(SocialResultCode.SOCIAL_UNBIND_REJECTED, message);
    }

    public SocialUnbindRejectedException(String message, Throwable cause) {
        super(SocialResultCode.SOCIAL_UNBIND_REJECTED, message, cause);
    }
}
