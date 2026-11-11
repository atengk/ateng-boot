package io.github.atengk.boot.sample.admin.provider;

import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.admin.provider.AdminAuthProvider;
import io.github.atengk.boot.admin.provider.DefaultPropertiesAdminAuthProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 样例自定义控制台登录认证 SPI 实现
 * 先执行禁用账号策略拦截，再委托默认配置属性认证并统计认证次数（演示企业级认证扩展接管）
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
    private final AtomicLong successCount = new AtomicLong(0);

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
        boolean matched = defaultProvider.authenticate(normalizedUser, password);
        if (matched) {
            successCount.incrementAndGet();
        }
        return matched;
    }

    /**
     * 获取本次应用运行期内通过控制台认证的累计成功次数
     *
     * @return 成功认证次数
     */
    public long getSuccessCount() {
        return successCount.get();
    }
}
