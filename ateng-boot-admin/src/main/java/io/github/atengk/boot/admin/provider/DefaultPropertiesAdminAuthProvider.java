package io.github.atengk.boot.admin.provider;

import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

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
        boolean userMatch = constantTimeEquals(username, properties.getUsername());
        boolean passMatch = constantTimeEquals(password, properties.getPassword());
        return userMatch && passMatch;
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
