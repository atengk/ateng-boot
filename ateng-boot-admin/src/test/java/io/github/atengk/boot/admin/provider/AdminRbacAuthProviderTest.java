package io.github.atengk.boot.admin.provider;

import cn.dev33.satoken.secure.BCrypt;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多角色 RBAC 认证、BCrypt 口令与 AdminAuthProvider SPI 向后兼容单元测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("AdminRole / AdminConsolePrincipal / 多账号认证 RBAC 测试")
class AdminRbacAuthProviderTest {

    @Test
    @DisplayName("仅实现 authenticate 的旧版 SPI 扩展平滑兼容，默认签发 ADMIN 角色主体")
    void shouldBridgeLegacyAuthenticateSpiToAdminPrincipal() {
        AdminAuthProvider legacyProvider = (username, password) ->
                "legacy-ops".equals(username) && "legacy-pass".equals(password);

        Optional<AdminConsolePrincipal> principal = legacyProvider.authenticatePrincipal(" legacy-ops ", "legacy-pass");
        assertThat(principal).isEmpty();

        Optional<AdminConsolePrincipal> exact = legacyProvider.authenticatePrincipal("legacy-ops", "legacy-pass");
        assertThat(exact).isPresent();
        assertThat(exact.get().getName()).isEqualTo("legacy-ops");
        assertThat(exact.get().getRoles()).containsExactly(AdminRole.ADMIN);
        assertThat(exact.get().hasWritePermission()).isTrue();
        assertThat(exact.get().hasAdminPermission()).isTrue();
    }

    @Test
    @DisplayName("配置非空 users 多账号列表后自动关闭默认 admin/admin 兜底，并精准签发各自角色")
    void shouldDisableDefaultAdminFallbackWhenUsersConfigured() {
        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        AtengAdminServerProperties.ConsoleUserProperties viewer = new AtengAdminServerProperties.ConsoleUserProperties();
        viewer.setUsername("observer");
        viewer.setPassword("viewer-pass");
        viewer.setRoles(List.of(AdminRole.VIEWER));

        AtengAdminServerProperties.ConsoleUserProperties operator = new AtengAdminServerProperties.ConsoleUserProperties();
        operator.setUsername("sre-op");
        operator.setPassword("{bcrypt}" + BCrypt.hashpw("Op#Secret2026"));
        operator.setRoles(List.of(AdminRole.OPERATOR));

        AtengAdminServerProperties.ConsoleUserProperties disabledAdmin = new AtengAdminServerProperties.ConsoleUserProperties();
        disabledAdmin.setUsername("disabled-admin");
        disabledAdmin.setPassword("123456");
        disabledAdmin.setRoles(List.of(AdminRole.ADMIN));
        disabledAdmin.setEnabled(false);

        properties.setUsers(List.of(viewer, operator, disabledAdmin));

        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-21T03:00:00Z"), ZoneOffset.UTC);
        DefaultPropertiesAdminAuthProvider provider = new DefaultPropertiesAdminAuthProvider(properties, fixedClock);

        // 1. 默认 admin/admin 被自动禁用
        assertThat(provider.authenticate("admin", "admin")).isFalse();
        assertThat(provider.authenticatePrincipal("admin", "admin")).isEmpty();

        // 2. VIEWER 明文账号认证成功且无写/超管权限
        Optional<AdminConsolePrincipal> viewerPrincipal = provider.authenticatePrincipal("observer", "viewer-pass");
        assertThat(viewerPrincipal).isPresent();
        assertThat(viewerPrincipal.get().getRoles()).containsExactly(AdminRole.VIEWER);
        assertThat(viewerPrincipal.get().hasWritePermission()).isFalse();
        assertThat(viewerPrincipal.get().hasAdminPermission()).isFalse();
        assertThat(viewerPrincipal.get().getAuthenticatedAt()).isEqualTo(Instant.parse("2026-11-21T03:00:00Z"));

        // 3. OPERATOR {bcrypt} 哈希校验成功且拥有写权限、无超管权限
        Optional<AdminConsolePrincipal> opPrincipal = provider.authenticatePrincipal("sre-op", "Op#Secret2026");
        assertThat(opPrincipal).isPresent();
        assertThat(opPrincipal.get().getRoles()).containsExactly(AdminRole.OPERATOR);
        assertThat(opPrincipal.get().hasWritePermission()).isTrue();
        assertThat(opPrincipal.get().hasAdminPermission()).isFalse();
        assertThat(provider.authenticate("sre-op", "wrong-pass")).isFalse();

        // 4. 禁用账号禁止登录
        assertThat(provider.authenticate("disabled-admin", "123456")).isFalse();
    }

    @Test
    @DisplayName("畸形 {bcrypt} 哈希安全拒绝不抛异常，AdminRole 契约支持 IBaseEnum 与大小写解析")
    void shouldRejectMalformedBcryptSafelyAndResolveRoles() {
        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        AtengAdminServerProperties.ConsoleUserProperties malformedUser = new AtengAdminServerProperties.ConsoleUserProperties();
        malformedUser.setUsername("broken-bcrypt");
        malformedUser.setPassword("{bcrypt}not-a-valid-bcrypt-hash");
        malformedUser.setRoles(List.of(AdminRole.ADMIN));
        properties.setUsers(List.of(malformedUser));

        DefaultPropertiesAdminAuthProvider provider = new DefaultPropertiesAdminAuthProvider(properties);
        assertThat(provider.authenticate("broken-bcrypt", "anything")).isFalse();

        assertThat(AdminRole.fromCode("viewer")).contains(AdminRole.VIEWER);
        assertThat(AdminRole.fromCode("OPERATOR")).contains(AdminRole.OPERATOR);
        assertThat(AdminRole.fromCode("unknown")).isEmpty();
        assertThat(AdminRole.ADMIN.getCode()).isEqualTo("ADMIN");
        assertThat(AdminRole.VIEWER.hasWritePermission()).isFalse();
        assertThat(AdminRole.OPERATOR.hasWritePermission()).isTrue();
        assertThat(AdminRole.ADMIN.hasAdminPermission()).isTrue();

        AdminConsolePrincipal principal = AdminConsolePrincipal.of("alice", Set.of(AdminRole.VIEWER));
        assertThat(principal.getMaskedUsername()).isEqualTo("al**e");
        assertThat(principal.getRoleCodes()).containsExactly("VIEWER");
    }
}
