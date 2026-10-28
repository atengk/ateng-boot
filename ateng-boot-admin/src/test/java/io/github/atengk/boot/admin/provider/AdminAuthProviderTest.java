package io.github.atengk.boot.admin.provider;

import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AdminAuthProvider 身份认证提供者单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("AdminAuthProvider 身份认证提供者单元测试")
class AdminAuthProviderTest {

    @Test
    @DisplayName("默认配置下使用 admin/admin 认证成功")
    void shouldAuthenticateWithDefaultCredentials() {
        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        AdminAuthProvider authProvider = new DefaultPropertiesAdminAuthProvider(properties);

        assertThat(authProvider.authenticate("admin", "admin")).isTrue();
        assertThat(authProvider.authenticate("admin", "wrong-password")).isFalse();
        assertThat(authProvider.authenticate("wrong-user", "admin")).isFalse();
    }

    @Test
    @DisplayName("自定义账号密码配置能够正确校验")
    void shouldAuthenticateWithCustomCredentials() {
        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        properties.setUsername("ops-master");
        properties.setPassword("P@ssw0rd123");
        AdminAuthProvider authProvider = new DefaultPropertiesAdminAuthProvider(properties);

        assertThat(authProvider.authenticate("ops-master", "P@ssw0rd123")).isTrue();
        assertThat(authProvider.authenticate("admin", "admin")).isFalse();
    }

    @Test
    @DisplayName("防御性测试：入参为空或属性为空时安全返回 false 不抛异常")
    void shouldHandleNullInputsSafely() {
        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        AdminAuthProvider authProvider = new DefaultPropertiesAdminAuthProvider(properties);

        assertThat(authProvider.authenticate(null, "admin")).isFalse();
        assertThat(authProvider.authenticate("admin", null)).isFalse();
        assertThat(authProvider.authenticate(null, null)).isFalse();

        AdminAuthProvider nullPropertiesProvider = new DefaultPropertiesAdminAuthProvider(null);
        assertThat(nullPropertiesProvider.authenticate("admin", "admin")).isFalse();
    }
}
