package io.github.atengk.boot.social.exception;

import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.social.code.SocialResultCode;

/**
 * 社交账号已被其他用户绑定冲突受控业务异常
 * <p>
 * 当在两阶段绑定或已登录直绑流程中，检测到待绑定的第三方社交账号已关联其他本地用户时抛出，
 * 彻底防御跨本地用户的账号抢占与越权串号风险。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-07
 */
public class SocialAlreadyBoundException extends SocialException {

    private static final long serialVersionUID = 1L;

    public SocialAlreadyBoundException(String message) {
        super(SocialResultCode.SOCIAL_ALREADY_BOUND, message);
    }

    public SocialAlreadyBoundException(String message, Throwable cause) {
        super(SocialResultCode.SOCIAL_ALREADY_BOUND, message, cause);
    }

    public SocialAlreadyBoundException(IResultCode resultCode, String message) {
        super(resultCode, message);
    }

    public SocialAlreadyBoundException(IResultCode resultCode, String message, Throwable cause) {
        super(resultCode, message, cause);
    }
}
