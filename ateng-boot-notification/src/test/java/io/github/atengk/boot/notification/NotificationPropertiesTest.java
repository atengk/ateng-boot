package io.github.atengk.boot.notification;

import io.github.atengk.boot.notification.model.Platform;
import io.github.atengk.boot.notification.properties.NotificationChannelProperties;
import io.github.atengk.boot.notification.properties.NotificationProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 协同通知配置属性与凭据脱敏单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
class NotificationPropertiesTest {

    @Test
    @DisplayName("验证 NotificationProperties 生产级默认值配置")
    void testDefaultValues() {
        NotificationProperties properties = new NotificationProperties();

        assertTrue(properties.isEnabled());
        assertEquals("ops-alert", properties.getDefaultChannel());
        assertEquals(Duration.ofSeconds(3), properties.getConnectTimeout());
        assertEquals(Duration.ofSeconds(5), properties.getReadTimeout());

        // 频控默认值
        assertNotNull(properties.getRateLimit());
        assertTrue(properties.getRateLimit().isEnabled());
        assertFalse(properties.getRateLimit().isDistributed());
        assertEquals(Duration.ofSeconds(2), properties.getRateLimit().getMaxWaitDuration());

        // 告警默认值
        assertNotNull(properties.getAlert());
        assertTrue(properties.getAlert().isEnabled());
        assertEquals(Duration.ofSeconds(60), properties.getAlert().getSuppressWindow());
        assertTrue(properties.getAlert().isIncludeArgs());

        // 渠道映射默认为空非 null
        assertNotNull(properties.getChannels());
        assertTrue(properties.getChannels().isEmpty());
    }

    @Test
    @DisplayName("验证 Platform 枚举解析行为与大小写兼容性")
    void testPlatformEnum() {
        assertEquals(Platform.DINGTALK, Platform.fromCode("dingtalk"));
        assertEquals(Platform.DINGTALK, Platform.fromCode("DINGTALK"));
        assertEquals(Platform.WECOM, Platform.fromCode("wecom"));
        assertEquals(Platform.WECOM, Platform.fromCode("WECOM"));
        assertEquals(Platform.FEISHU, Platform.fromCode("feishu"));
        assertEquals(Platform.FEISHU, Platform.fromCode("FEISHU"));
        assertEquals(Platform.GENERIC, Platform.fromCode("generic"));
        assertEquals(Platform.GENERIC, Platform.fromCode("GENERIC"));

        assertNull(Platform.fromCode("unknown"));
        assertNull(Platform.fromCode(null));
        assertNull(Platform.fromCode("   "));
    }

    @Test
    @DisplayName("验证 NotificationChannelProperties.toString() 凭证零泄露安全脱敏")
    void testChannelPropertiesToStringMasking() {
        NotificationChannelProperties channel = new NotificationChannelProperties();
        channel.setPlatform(Platform.DINGTALK);
        channel.setWebhook("https://oapi.dingtalk.com/robot/send?access_token=68abcdef0123456789abcdef0123456789");
        channel.setSecret("SEC9876543210fedcba9876543210fedcba");
        channel.setConnectTimeout(Duration.ofSeconds(2));
        channel.setPermitsPerMinute(20);

        String str = channel.toString();
        assertNotNull(str);

        // 断言明文密钥和 Token 不出现在 toString 中
        assertFalse(str.contains("68abcdef0123456789abcdef0123456789"), "明文 Token 严禁出现在 toString 中");
        assertFalse(str.contains("SEC9876543210fedcba9876543210fedcba"), "明文 Secret 严禁出现在 toString 中");

        // 断言包含脱敏打码特征
        assertTrue(str.contains("***"), "日志输出必须包含掩码星号");
    }
}
