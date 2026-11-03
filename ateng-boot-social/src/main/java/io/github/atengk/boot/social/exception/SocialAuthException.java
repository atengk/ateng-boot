package io.github.atengk.boot.social.exception;

import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.social.code.SocialResultCode;

/**
 * 社交授权认证失败受控业务异常
 * <p>
 * 当第三方平台 OAuth2 授权交互失败、Token 换码失败、接口返回错误或直传凭据无效时抛出。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class SocialAuthException extends SocialException {

    private static final long serialVersionUID = 1L;

    public SocialAuthException(String message) {
        super(SocialResultCode.SOCIAL_AUTH_FAILED, message);
    }

    public SocialAuthException(String message, Throwable cause) {
        super(SocialResultCode.SOCIAL_AUTH_FAILED, message, cause);
    }

    public SocialAuthException(IResultCode resultCode, String message) {
        super(resultCode, message);
    }

    public SocialAuthException(IResultCode resultCode, String message, Throwable cause) {
        super(resultCode, message, cause);
    }
}
