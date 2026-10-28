package io.github.atengk.boot.mail.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 邮件配置属性测试用例
 *
 * @author Ateng
 * @since 2026-10-22
 */
class MailPropertiesTest {

    @Test
    @DisplayName("测试配置属性生产默认值")
    void testProductionDefaults() {
        MailProperties properties = new MailProperties();

        assertTrue(properties.isEnabled());
        assertEquals("default", properties.getDefaultAccount());
        assertEquals(Duration.ofSeconds(5), properties.getConnectionTimeout());
        assertEquals(Duration.ofSeconds(10), properties.getReadTimeout());
        assertEquals(Duration.ofSeconds(10), properties.getWriteTimeout());
        assertEquals(DataSize.ofMegabytes(20), properties.getMaxAttachmentSize());
        assertEquals(DataSize.ofMegabytes(50), properties.getMaxTotalSize());
        assertEquals(100, properties.getMaxDynamicAccounts());

        assertNotNull(properties.getAsync());
        assertTrue(properties.getAsync().isEnabled());
        assertEquals("mail-async-", properties.getAsync().getThreadNamePrefix());
        assertEquals("mail-vt-async-", properties.getAsync().getVirtualThreadNamePrefix());

        assertEquals(465, properties.getPort());
        assertEquals("smtp", properties.getProtocol());
        assertTrue(properties.isSsl());
        assertFalse(properties.isStarttls());
        assertNotNull(properties.getProperties());
        assertNotNull(properties.getAccounts());
    }

    @Test
    @DisplayName("测试账号配置属性默认值与赋值")
    void testAccountProperties() {
        MailAccountProperties account = new MailAccountProperties();
        assertEquals(465, account.getPort());
        assertEquals("smtp", account.getProtocol());
        assertTrue(account.isSsl());
        assertFalse(account.isStarttls());

        account.setHost("smtp.example.com");
        account.setUsername("user@example.com");
        account.setPassword("secret");
        account.setDefaultFrom("noreply@example.com");
        account.setDefaultFromName("阿腾通知");

        assertEquals("smtp.example.com", account.getHost());
        assertEquals("user@example.com", account.getUsername());
        assertEquals("secret", account.getPassword());
        assertEquals("noreply@example.com", account.getDefaultFrom());
        assertEquals("noreply@example.com", account.getFrom());
        assertEquals("阿腾通知", account.getDefaultFromName());
        assertEquals("阿腾通知", account.getFromName());

        // 测试 from / fromName 优先覆盖与反向兼容
        account.setFrom("custom@example.com");
        account.setFromName("自定义发件人");
        assertEquals("custom@example.com", account.getFrom());
        assertEquals("custom@example.com", account.getDefaultFrom());
        assertEquals("自定义发件人", account.getFromName());
        assertEquals("自定义发件人", account.getDefaultFromName());
    }

    @Test
    @DisplayName("测试顶级配置的发信人别名双向自洽")
    void testTopLevelFromAliases() {
        MailProperties props = new MailProperties();
        props.setFrom("service@example.com");
        props.setFromName("服务通知");

        assertEquals("service@example.com", props.getFrom());
        assertEquals("service@example.com", props.getDefaultFrom());
        assertEquals("服务通知", props.getFromName());
        assertEquals("服务通知", props.getDefaultFromName());

        props.setDefaultFrom("system@example.com");
        props.setDefaultFromName("系统告警");
        assertEquals("system@example.com", props.getFrom());
        assertEquals("system@example.com", props.getDefaultFrom());
        assertEquals("系统告警", props.getFromName());
        assertEquals("系统告警", props.getDefaultFromName());
    }
}
