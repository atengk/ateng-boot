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

    @Test
    @DisplayName("测试动态账号 LRU 缓存复用与容量上限淘汰")
    void testDynamicAccountLruCacheAndEviction() {
        // 创建最大容量为 2 的动态账号注册表
        MailAccountRegistry registry = new MailAccountRegistry("default", 2);
        assertEquals(2, registry.getMaxDynamicAccounts());
        assertEquals(0, registry.dynamicSize());

        MailAccount rawAccount1 = MailAccount.builder().name("tenant1").host("smtp.tenant1.com").username("t1").build();
        MailAccount rawAccount2 = MailAccount.builder().name("tenant2").host("smtp.tenant2.com").username("t2").build();
        MailAccount rawAccount3 = MailAccount.builder().name("tenant3").host("smtp.tenant3.com").username("t3").build();

        // 1. 注册 tenant1
        MailAccount managed1 = registry.getOrCreateDynamic(rawAccount1, null);
        assertNotNull(managed1.getSender());
        assertEquals(1, registry.dynamicSize());

        // 再次获取同一指纹账号，应直接复用底层 Sender
        MailAccount managed1Again = registry.getOrCreateDynamic(rawAccount1, null);
        assertSame(managed1.getSender(), managed1Again.getSender(), "相同指纹的动态账号应复用物理 Sender 实例");
        assertEquals(1, registry.dynamicSize());

        // 2. 注册 tenant2，达到容量 2
        MailAccount managed2 = registry.getOrCreateDynamic(rawAccount2, null);
        assertEquals(2, registry.dynamicSize());

        // 3. 访问 tenant1，使其变为最近使用 (LRU 访问顺序更新)
        registry.getOrCreateDynamic(rawAccount1, null);

        // 4. 注册 tenant3，触发 LRU 淘汰最久未访问的 tenant2
        MailAccount managed3 = registry.getOrCreateDynamic(rawAccount3, null);
        assertEquals(2, registry.dynamicSize(), "动态账号条目数不得超过配置的最大上限");

        // 验证 tenant2 已被淘汰，若再次访问会重新创建新 Sender
        MailAccount managed2New = registry.getOrCreateDynamic(rawAccount2, null);
        assertNotSame(managed2.getSender(), managed2New.getSender(), "被淘汰的动态账号重新进入时应重新分配 Sender");

        // 5. 测试清理
        registry.clearDynamic();
        assertEquals(0, registry.dynamicSize());
    }
}
