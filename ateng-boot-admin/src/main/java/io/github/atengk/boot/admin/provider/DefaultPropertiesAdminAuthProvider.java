package io.github.atengk.boot.admin.provider;

import cn.dev33.satoken.secure.BCrypt;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.admin.security.AdminSecretMatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 基于配置属性的多账号 RBAC 与向后兼容认证提供者默认实现
 * <p>
 * 1. 当配置了非空 {@code ateng.boot.admin.server.users} 账号表时，自动关闭默认单账号（{@code admin/admin}）兜底；<br>
 * 2. 支持明文（恒定时间比对）与 {@code {bcrypt}} 前缀口令哈希校验；<br>
 * 3. 当 {@code users} 为空时平滑兼容存量单账号配置并授予 {@link AdminRole#ADMIN} 角色。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Slf4j
public class DefaultPropertiesAdminAuthProvider implements AdminAuthProvider {

    private static final String BCRYPT_PREFIX = "{bcrypt}";

    private final AtengAdminServerProperties properties;
    private final Clock clock;

    public DefaultPropertiesAdminAuthProvider(AtengAdminServerProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public DefaultPropertiesAdminAuthProvider(AtengAdminServerProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public boolean authenticate(String username, String password) {
        return authenticatePrincipal(username, password).isPresent();
    }

    @Override
    public Optional<AdminConsolePrincipal> authenticatePrincipal(String username, String password) {
        if (username == null || password == null || properties == null) {
            return Optional.empty();
        }
        List<AtengAdminServerProperties.ConsoleUserProperties> configuredUsers = properties.getUsers();
        if (configuredUsers != null && !configuredUsers.isEmpty()) {
            return authenticateFromUsers(configuredUsers, username, password);
        }
        return authenticateSingleLegacyAccount(username, password);
    }

    private Optional<AdminConsolePrincipal> authenticateFromUsers(
            List<AtengAdminServerProperties.ConsoleUserProperties> configuredUsers,
            String username,
            String password) {
        AdminConsolePrincipal matchedPrincipal = null;
        for (AtengAdminServerProperties.ConsoleUserProperties user : configuredUsers) {
            if (user == null || !StringUtils.hasText(user.getUsername())) {
                continue;
            }
            boolean userMatch = AdminSecretMatcher.constantTimeEquals(username, user.getUsername());
            boolean passMatch = matchesConfiguredPassword(password, user.getPassword());
            if (userMatch && passMatch && user.isEnabled() && matchedPrincipal == null) {
                matchedPrincipal = AdminConsolePrincipal.of(
                        user.getUsername().trim(),
                        resolveRoles(user.getRoles()),
                        user.getAttributes(),
                        clock.instant()
                );
            }
        }
        return Optional.ofNullable(matchedPrincipal);
    }

    private Optional<AdminConsolePrincipal> authenticateSingleLegacyAccount(String username, String password) {
        boolean userMatch = AdminSecretMatcher.constantTimeEquals(username, properties.getUsername());
        boolean passMatch = matchesConfiguredPassword(password, properties.getPassword());
        if (!userMatch || !passMatch) {
            return Optional.empty();
        }
        return Optional.of(AdminConsolePrincipal.of(
                properties.getUsername().trim(),
                Set.of(AdminRole.ADMIN),
                null,
                clock.instant()
        ));
    }

    private static Set<AdminRole> resolveRoles(List<AdminRole> configuredRoles) {
        if (configuredRoles == null || configuredRoles.isEmpty()) {
            return EnumSet.of(AdminRole.VIEWER);
        }
        EnumSet<AdminRole> roles = EnumSet.noneOf(AdminRole.class);
        for (AdminRole role : configuredRoles) {
            if (role != null) {
                roles.add(role);
            }
        }
        return roles.isEmpty() ? EnumSet.of(AdminRole.VIEWER) : roles;
    }

    private static boolean matchesConfiguredPassword(String rawPassword, String configuredPassword) {
        if (rawPassword == null || configuredPassword == null || configuredPassword.isEmpty()) {
            return false;
        }
        if (configuredPassword.startsWith(BCRYPT_PREFIX)) {
            String bcryptHash = configuredPassword.substring(BCRYPT_PREFIX.length());
            if (bcryptHash.isBlank()) {
                return false;
            }
            try {
                return BCrypt.checkpw(rawPassword, bcryptHash);
            } catch (IllegalArgumentException ex) {
                log.warn("[Admin Auth] 无效的 BCrypt 口令哈希格式，已安全拒绝认证: {}", ex.getMessage());
                return false;
            }
        }
        return AdminSecretMatcher.constantTimeEquals(rawPassword, configuredPassword);
    }
}
