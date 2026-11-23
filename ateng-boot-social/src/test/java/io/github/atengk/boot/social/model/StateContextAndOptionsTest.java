package io.github.atengk.boot.social.model;

import io.github.atengk.boot.core.enums.IBaseEnum;
import io.github.atengk.boot.core.util.EnumUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StateContext 与 SocialLoginOptions 契约测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class StateContextAndOptionsTest {

    @Test
    @DisplayName("验证 StateContext 装载多租户、深链跳转与业务扩展参数")
    void testStateContext() {
        StateContext context = StateContext.builder()
                .tenantId("tenant-10086")
                .redirectUrl("https://app.example.com/oauth/callback")
                .extraParams(Map.of("channel", "invite", "code", 999))
                .build();

        assertThat(context.getTenantId()).isEqualTo("tenant-10086");
        assertThat(context.getRedirectUrl()).isEqualTo("https://app.example.com/oauth/callback");
        assertThat(context.getExtraParams()).containsEntry("channel", "invite");
        assertThat(context.getCreatedAt()).isPositive();
    }

    @Test
    @DisplayName("验证 SocialLoginOptions 装载设备类型、账号体系与超时")
    void testSocialLoginOptions() {
        SocialLoginOptions options = SocialLoginOptions.builder()
                .deviceType("MINI_PROGRAM")
                .loginType("user")
                .timeout(Duration.ofHours(2))
                .isLastingCookie(true)
                .extraData(Map.of("ip", "127.0.0.1"))
                .build();

        assertThat(options.getDeviceType()).isEqualTo("MINI_PROGRAM");
        assertThat(options.getLoginType()).isEqualTo("user");
        assertThat(options.getTimeout()).isEqualTo(Duration.ofHours(2));
        assertThat(options.getIsLastingCookie()).isTrue();
        assertThat(options.getExtraData()).containsEntry("ip", "127.0.0.1");
    }

    @Test
    @DisplayName("验证 SocialAuthState 实现 IBaseEnum 强类型通用枚举契约")
    void testSocialAuthStateImplementsIBaseEnum() {
        assertThat(SocialAuthState.LOGGED_IN).isInstanceOf(IBaseEnum.class);
        assertThat(SocialAuthState.LOGGED_IN.getCode()).isEqualTo("LOGGED_IN");
        assertThat(SocialAuthState.LOGGED_IN.getValue()).isEqualTo("LOGGED_IN");
        assertThat(SocialAuthState.LOGGED_IN.getLabel()).isNotBlank();
        assertThat(SocialAuthState.LOGGED_IN.getDescription()).isEqualTo(SocialAuthState.LOGGED_IN.getLabel());
        assertThat(SocialAuthState.REQUIRE_BIND.getCode()).isEqualTo("REQUIRE_BIND");
        assertThat(EnumUtil.fromCode(SocialAuthState.class, "LOGGED_IN")).contains(SocialAuthState.LOGGED_IN);
        assertThat(EnumUtil.isValidCode(SocialAuthState.class, "REQUIRE_BIND")).isTrue();
    }
}
