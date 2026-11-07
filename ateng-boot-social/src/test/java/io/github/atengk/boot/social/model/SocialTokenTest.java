package io.github.atengk.boot.social.model;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.dromara.anduin.model.AuthToken;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SocialToken 领域模型与请求契约单元测试
 *
 * @author Ateng
 * @since 2026-11-07
 */
class SocialTokenTest {

    private final Validator validator = Validation.byDefaultProvider()
            .configure()
            .messageInterpolator(new ParameterMessageInterpolator())
            .buildValidatorFactory()
            .getValidator();

    @Test
    @DisplayName("1. fromAuthToken: 空入参防御保底返回 null")
    void testFromAuthTokenNull() {
        assertThat(SocialToken.fromAuthToken(null)).isNull();
    }

    @Test
    @DisplayName("2. fromAuthToken 与 toAuthToken 对称转换与属性保持")
    void testFromAndToAuthToken() {
        AuthToken authToken = AuthToken.builder()
                .accessToken("access_token_123")
                .refreshToken("refresh_token_456")
                .expireIn(7200)
                .openId("open_id_789")
                .unionId("union_id_abc")
                .scope("read,write")
                .build();

        SocialToken socialToken = SocialToken.fromAuthToken(authToken);
        assertThat(socialToken).isNotNull();
        assertThat(socialToken.getAccessToken()).isEqualTo("access_token_123");
        assertThat(socialToken.getRefreshToken()).isEqualTo("refresh_token_456");
        assertThat(socialToken.getExpireIn()).isEqualTo(7200);
        assertThat(socialToken.getExpireDuration()).isEqualTo(Duration.ofSeconds(7200));
        assertThat(socialToken.getOpenId()).isEqualTo("open_id_789");
        assertThat(socialToken.getUnionId()).isEqualTo("union_id_abc");
        assertThat(socialToken.getScope()).isEqualTo("read,write");
        assertThat(socialToken.getRawToken()).isSameAs(authToken);

        AuthToken converted = socialToken.toAuthToken();
        assertThat(converted.getAccessToken()).isEqualTo("access_token_123");
        assertThat(converted.getRefreshToken()).isEqualTo("refresh_token_456");
        assertThat(converted.getExpireIn()).isEqualTo(7200);
        assertThat(converted.getOpenId()).isEqualTo("open_id_789");
        assertThat(converted.getUnionId()).isEqualTo("union_id_abc");
        assertThat(converted.getScope()).isEqualTo("read,write");
    }

    @Test
    @DisplayName("3. SocialTokenRefreshRequest: JSR-303 格式校验")
    void testSocialTokenRefreshRequestValidation() {
        SocialTokenRefreshRequest blankReq = SocialTokenRefreshRequest.builder().refreshToken("  ").build();
        assertThat(validator.validate(blankReq)).isNotEmpty();

        SocialTokenRefreshRequest nullReq = new SocialTokenRefreshRequest();
        assertThat(validator.validate(nullReq)).isNotEmpty();

        SocialTokenRefreshRequest validReq = SocialTokenRefreshRequest.builder().refreshToken("valid_refresh_token").build();
        assertThat(validator.validate(validReq)).isEmpty();
    }

    @Test
    @DisplayName("4. SocialTokenRevokeRequest: JSR-303 格式校验")
    void testSocialTokenRevokeRequestValidation() {
        SocialTokenRevokeRequest blankReq = SocialTokenRevokeRequest.builder().accessToken("  ").build();
        assertThat(validator.validate(blankReq)).isNotEmpty();

        SocialTokenRevokeRequest nullReq = new SocialTokenRevokeRequest();
        assertThat(validator.validate(nullReq)).isNotEmpty();

        SocialTokenRevokeRequest validReq = SocialTokenRevokeRequest.builder().accessToken("valid_access_token").build();
        assertThat(validator.validate(validReq)).isEmpty();
    }
}
