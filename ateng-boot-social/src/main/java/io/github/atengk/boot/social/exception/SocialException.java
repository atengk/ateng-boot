package io.github.atengk.boot.social.exception;

import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.social.code.SocialResultCode;

/**
 * 社交登录与 OAuth2 受控业务异常基类
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class SocialException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public SocialException(IResultCode resultCode) {
        super(resultCode);
    }

    public SocialException(IResultCode resultCode, String msg) {
        super(resultCode, msg);
    }

    public SocialException(IResultCode resultCode, Throwable cause) {
        super(resultCode, cause);
    }

    public SocialException(IResultCode resultCode, String msg, Throwable cause) {
        super(resultCode, msg, cause);
    }

    public SocialException(String msg) {
        super(SocialResultCode.SOCIAL_ERROR, msg);
    }

    public SocialException(String msg, Throwable cause) {
        super(SocialResultCode.SOCIAL_ERROR, msg, cause);
    }
}
