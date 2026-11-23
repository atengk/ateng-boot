package io.github.atengk.boot.sample.social.provider;

import org.dromara.anduin.cache.AuthStateCache;
import org.dromara.anduin.config.AuthConfig;
import org.dromara.anduin.config.AuthSource;
import org.dromara.anduin.model.AuthCallback;
import org.dromara.anduin.model.AuthToken;
import org.dromara.anduin.model.AuthUser;
import org.dromara.anduin.request.AuthDefaultRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 企业自建 SSO / IAM 自定义第三方平台扩展源样例
 * <p>
 * 演示通过实现 {@link AuthSource} 并注册为 Spring Bean，将企业私有 OAuth2 / Keycloak / CAS 平台
 * 无缝接入 {@code SocialAppFactory} 与 {@code SocialAuthTemplate} 统一认证流水线。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Component
public class SampleEnterpriseSsoAuthSource implements AuthSource {

    public static final String SOURCE_NAME = "ENTERPRISE_SSO";
    private static final SampleEnterpriseSsoAuthSource INSTANCE = new SampleEnterpriseSsoAuthSource();

    @Override
    public String authorize() {
        return "https://sso.ateng.example.com/oauth2/authorize";
    }

    @Override
    public String accessToken() {
        return "https://sso.ateng.example.com/oauth2/token";
    }

    @Override
    public String userInfo() {
        return "https://sso.ateng.example.com/oauth2/userinfo";
    }

    @Override
    public String getName() {
        return SOURCE_NAME;
    }

    @Override
    public Class<? extends AuthDefaultRequest> getTargetClass() {
        return EnterpriseSsoAuthRequest.class;
    }

    /**
     * 企业自建 SSO 平台专有请求实现
     */
    public static class EnterpriseSsoAuthRequest extends AuthDefaultRequest {

        public EnterpriseSsoAuthRequest(AuthConfig config) {
            super(config, INSTANCE);
        }

        public EnterpriseSsoAuthRequest(AuthConfig config, AuthStateCache authStateCache) {
            super(config, INSTANCE, authStateCache);
        }

        @Override
        public AuthToken getAccessToken(AuthCallback authCallback) {
            String response = doPostAuthorizationCode(authCallback.getCode());
            JsonNode object = toNode(response);
            validateResponse(object);
            return AuthToken.builder()
                    .accessToken(getStringValue(object, "access_token"))
                    .refreshToken(getStringValue(object, "refresh_token"))
                    .expireIn(getIntValue(object, "expires_in"))
                    .idToken(getStringValue(object, "id_token"))
                    .openId(getStringValue(object, "sub"))
                    .build();
        }

        @Override
        public AuthUser getUserInfo(AuthToken authToken) {
            String response = doGetUserInfo(authToken);
            JsonNode object = toNode(response);
            validateResponse(object);
            String openId = getStringValue(object, "sub");
            if (openId == null || openId.isBlank()) {
                openId = authToken.getOpenId();
            }
            return AuthUser.builder()
                    .rawUserInfo(object)
                    .uuid(openId)
                    .username(getStringValue(object, "username"))
                    .nickname(getStringValue(object, "nickname"))
                    .email(getStringValue(object, "email"))
                    .avatar(getStringValue(object, "avatar"))
                    .token(authToken)
                    .source(SOURCE_NAME)
                    .build();
        }
    }
}
