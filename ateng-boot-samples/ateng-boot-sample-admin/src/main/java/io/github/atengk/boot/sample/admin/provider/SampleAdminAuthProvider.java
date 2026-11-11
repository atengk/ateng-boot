package io.github.atengk.boot.sample.admin.provider;

import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.admin.provider.AdminAuthProvider;
import io.github.atengk.boot.admin.provider.DefaultPropertiesAdminAuthProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * 样例自定义控制台登录认证 SPI 实现
 * 先执行禁用账号策略拦截，再委托默认配置属性认证（演示无状态企业级认证扩展接管）
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Component
public class SampleAdminAuthProvider implements AdminAuthProvider {

    /**
     * 演示被策略冻结禁止登录的账号集合
     */
    private static final Set<String> LOCKED_ACCOUNTS = Set.of("locked-operator");

    private final DefaultPropertiesAdminAuthProvider defaultProvider;

    public SampleAdminAuthProvider(AtengAdminServerProperties properties) {
        this.defaultProvider = new DefaultPropertiesAdminAuthProvider(properties);
    }

    @Override
    public boolean authenticate(String username, String password) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            return false;
        }
        String normalizedUser = username.trim();
        if (LOCKED_ACCOUNTS.contains(normalizedUser)) {
            return false;
        }
        return defaultProvider.authenticate(normalizedUser, password);
    }
}
