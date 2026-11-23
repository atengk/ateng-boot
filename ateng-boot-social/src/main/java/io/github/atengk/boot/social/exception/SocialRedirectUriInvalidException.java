package io.github.atengk.boot.social.exception;

import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.social.code.SocialResultCode;

/**
 * 社交登录重定向地址非法异常
 * <p>
 * 当 OAuth2 授权请求中的重定向地址 redirectUrl 包含危险伪协议、协议相对路径、非法格式或未命中白名单域名时抛出。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-07
 */
public class SocialRedirectUriInvalidException extends SocialException {

    private static final long serialVersionUID = 1L;

    public SocialRedirectUriInvalidException(String message) {
        super(SocialResultCode.SOCIAL_REDIRECT_URI_INVALID, message);
    }

    public SocialRedirectUriInvalidException(String message, Throwable cause) {
        super(SocialResultCode.SOCIAL_REDIRECT_URI_INVALID, message, cause);
    }

    public SocialRedirectUriInvalidException(IResultCode resultCode, String message) {
        super(resultCode, message);
    }

    public SocialRedirectUriInvalidException(IResultCode resultCode, String message, Throwable cause) {
        super(resultCode, message, cause);
    }
}
