package io.github.atengk.boot.social.model;

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
}
