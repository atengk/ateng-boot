package io.github.atengk.boot.social.exception;

import io.github.atengk.boot.social.code.SocialResultCode;

/**
 * 第三方社交应用未找到或未配置异常
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class SocialAppNotFoundException extends SocialException {

    private static final long serialVersionUID = 1L;

    public SocialAppNotFoundException(String appKey) {
        super(SocialResultCode.SOCIAL_APP_NOT_FOUND, "未找到应用标识为 [" + appKey + "] 的社交应用配置或路由实例");
    }

    public SocialAppNotFoundException(String appKey, Throwable cause) {
        super(SocialResultCode.SOCIAL_APP_NOT_FOUND, "未找到应用标识为 [" + appKey + "] 的社交应用配置或路由实例", cause);
    }
}
