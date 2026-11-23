package io.github.atengk.boot.social.session;

import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.boot.social.model.SocialLoginOptions;
import io.github.atengk.boot.social.model.SocialUser;
import io.github.atengk.boot.social.test.MockSaTokenTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SaTokenSocialSessionManager 单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class SaTokenSocialSessionManagerTest {

    private SaTokenSocialSessionManager sessionManager;

    @BeforeEach
    void setUp() {
        MockSaTokenTestHelper.setUp();
        sessionManager = new SaTokenSocialSessionManager();
    }

    @AfterEach
    void tearDown() {
        MockSaTokenTestHelper.tearDown();
    }

    @Test
    @DisplayName("空入参防御：userId 为空时快速失败")
    void testCreateSessionWithNullUserId() {
        assertThatThrownBy(() -> sessionManager.createSession(null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("本地用户标识 (userId) 不能为空");

        assertThatThrownBy(() -> sessionManager.createSession("   ", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("正常签发登录会话与 TokenInfo")
    void testCreateSessionSuccess() {
        SocialUser socialUser = SocialUser.builder()
                .openId("test_open_id")
                .nickname("阿腾")
                .build();

        SocialLoginOptions options = SocialLoginOptions.builder()
                .deviceType("PC")
                .timeout(Duration.ofHours(2))
                .extraData(Map.of("tenantId", "tenant_001"))
                .build();

        Object tokenInfo = sessionManager.createSession("user_1001", options, socialUser);
        assertThat(tokenInfo).isNotNull();
        assertThat(StpUtil.isLogin()).isTrue();
        assertThat(StpUtil.getLoginIdAsString()).contains("user_1001");
    }
}
