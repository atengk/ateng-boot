package io.github.atengk.boot.admin.provider;

import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.admin.security.AdminSecretMatcher;

/**
 * 基于配置属性的比对认证提供者默认实现
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class DefaultPropertiesAdminAuthProvider implements AdminAuthProvider {

    private final AtengAdminServerProperties properties;

    public DefaultPropertiesAdminAuthProvider(AtengAdminServerProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean authenticate(String username, String password) {
        if (username == null || password == null || properties == null) {
            return false;
        }
        boolean userMatch = AdminSecretMatcher.constantTimeEquals(username, properties.getUsername());
        boolean passMatch = AdminSecretMatcher.constantTimeEquals(password, properties.getPassword());
        return userMatch && passMatch;
    }
}
