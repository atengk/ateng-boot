package io.github.atengk.boot.social.model;

/**
 * 社交登录认证结果状态枚举
 * <p>
 * 用于标识用户在完成第三方社交平台认证后的本地账号流转状态。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
public enum SocialAuthState {

    /**
     * 认证成功且已关联本地用户，已正常签发登录会话
     */
    LOGGED_IN,

    /**
     * 第三方认证通过但尚未关联本地用户，已签发两阶段绑定票据，需引导绑定或注册
     */
    REQUIRE_BIND
}
