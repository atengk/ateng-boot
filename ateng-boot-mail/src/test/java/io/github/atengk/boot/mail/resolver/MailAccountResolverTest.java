package io.github.atengk.boot.mail.resolver;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.mail.model.MailMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 发件账号解析器单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
class MailAccountResolverTest {

    private final TenantMailAccountResolver resolver = new TenantMailAccountResolver();

    @BeforeEach
    @AfterEach
    void cleanContext() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("测试显式指定账号优先返回")
    void testExplicitAccountPrecedence() {
        TenantContextHolder.setTenantId("tenant-999");

        MailMessage message = new MailMessage().account("explicit-account");
        String resolved = resolver.resolveAccount(message);

        assertEquals("explicit-account", resolved, "显式指定的账号优先级必须高于租户上下文");
    }

    @Test
    @DisplayName("测试基于 TenantContextHolder 自动解析租户账号")
    void testResolveFromTenantContext() {
        TenantContextHolder.setTenantId("tenant-marketing");

        MailMessage message = new MailMessage();
        String resolved = resolver.resolveAccount(message);

        assertEquals("tenant-marketing", resolved);
    }

    @Test
    @DisplayName("测试租户上下文为空时返回 null 以便平滑回退")
    void testFallbackWhenTenantContextEmpty() {
        MailMessage message = new MailMessage();
        String resolved = resolver.resolveAccount(message);

        assertNull(resolved, "无租户上下文时应返回 null 供门面回退默认账号");
    }
}
