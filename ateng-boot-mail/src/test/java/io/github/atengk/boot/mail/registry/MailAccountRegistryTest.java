package io.github.atengk.boot.mail.registry;

import io.github.atengk.boot.mail.exception.MailSendException;
import io.github.atengk.boot.mail.model.MailAccount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 发信账号注册中心测试用例
 *
 * @author Ateng
 * @since 2026-10-22
 */
class MailAccountRegistryTest {

    @Test
    @DisplayName("测试账号注册与按名检索")
    void testRegisterAndGet() {
        MailAccountRegistry registry = new MailAccountRegistry("primary");
        assertEquals("primary", registry.getDefaultAccountName());
        assertEquals(0, registry.size());

        MailAccount account1 = MailAccount.builder().name("primary").host("smtp.primary.com").build();
        MailAccount account2 = MailAccount.builder().name("ops").host("smtp.ops.com").build();

        registry.register(account1);
        registry.register(account2);

        assertEquals(2, registry.size());
        assertTrue(registry.contains("primary"));
        assertTrue(registry.contains("ops"));
        assertFalse(registry.contains("marketing"));

        assertSame(account1, registry.get("primary"));
        assertSame(account2, registry.get("ops"));
        assertSame(account1, registry.getDefault());
    }

    @Test
    @DisplayName("测试空注册表获取默认账号抛出明确异常")
    void testGetDefaultWhenEmpty() {
        MailAccountRegistry registry = new MailAccountRegistry("default");
        assertThrows(MailSendException.class, registry::getDefault);
    }
}
