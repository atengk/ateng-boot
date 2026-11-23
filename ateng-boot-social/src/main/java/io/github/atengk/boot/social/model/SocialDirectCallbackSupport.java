package io.github.atengk.boot.social.model;

import org.dromara.anduin.model.AuthCallback;

/**
 * 直传登录与直绑请求通用回调转换辅助工具
 *
 * @author Ateng
 * @since 2026-11-21
 */
final class SocialDirectCallbackSupport {

    private SocialDirectCallbackSupport() {
    }

    static boolean isCredentialPresent(String code, String authCode, String oauthToken) {
        return hasText(code) || hasText(authCode) || hasText(oauthToken);
    }

    static boolean hasExtendedCallbackFields(String authCode, String oauthToken, String oauthVerifier, String user) {
        return hasText(authCode) || hasText(oauthToken) || hasText(oauthVerifier) || hasText(user);
    }

    static AuthCallback toAuthCallback(String code,
                                       String state,
                                       String authCode,
                                       String oauthToken,
                                       String oauthVerifier,
                                       String user) {
        return AuthCallback.builder()
                .code(code)
                .state(state)
                .auth_code(authCode)
                .oauth_token(oauthToken)
                .oauth_verifier(oauthVerifier)
                .user(user)
                .build();
    }

    private static boolean hasText(String str) {
        return str != null && !str.isBlank();
    }
}
