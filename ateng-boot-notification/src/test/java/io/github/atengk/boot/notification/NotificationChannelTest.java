package io.github.atengk.boot.notification;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.model.Platform;
import io.github.atengk.boot.notification.properties.NotificationChannelProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 通知渠道运行时模型测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("通知渠道运行时配置模型测试")
class NotificationChannelTest {

    @Test
    @DisplayName("测试正常构建渠道配置")
    void testBuildNotificationChannel() {
        NotificationChannel channel = NotificationChannel.builder()
                .name("ops-alert")
                .platform(Platform.DINGTALK)
                .webhook("https://oapi.dingtalk.com/robot/send?access_token=token1234567890abcdef")
                .secret("sec-1234567890abcdef")
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(4))
                .permitsPerMinute(20)
                .build();

        assertThat(channel.getName()).isEqualTo("ops-alert");
        assertThat(channel.getPlatform()).isEqualTo(Platform.DINGTALK);
        assertThat(channel.getWebhook()).contains("token1234567890abcdef");
        assertThat(channel.getSecret()).isEqualTo("sec-1234567890abcdef");
        assertThat(channel.getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(channel.getReadTimeout()).isEqualTo(Duration.ofSeconds(4));
        assertThat(channel.getPermitsPerMinute()).isEqualTo(20);

        // 验证 toString 脱敏安全保护
        String toString = channel.toString();
        assertThat(toString).doesNotContain("token1234567890");
        assertThat(toString).doesNotContain("sec-1234567890");
    }

    @Test
    @DisplayName("测试从属性对象构建渠道配置")
    void testFromProperties() {
        NotificationChannelProperties props = new NotificationChannelProperties();
        props.setPlatform(Platform.FEISHU);
        props.setWebhook("https://open.feishu.cn/open-apis/bot/v2/hook/token1234567890abcdef");
        props.setSecret("secret123456");
        props.setPermitsPerMinute(100);

        NotificationChannel channel = NotificationChannel.fromProperties("feishu-channel", props);
        assertThat(channel.getName()).isEqualTo("feishu-channel");
        assertThat(channel.getPlatform()).isEqualTo(Platform.FEISHU);
        assertThat(channel.getPermitsPerMinute()).isEqualTo(100);
    }

    @Test
    @DisplayName("测试非法参数校验触发断言拦截")
    void testValidationFailure() {
        // 名称为空
        assertThatThrownBy(() -> new NotificationChannel("", Platform.DINGTALK, "http://a.cn", null, null, null, null))
                .isInstanceOf(BusinessException.class);

        // 平台为空
        assertThatThrownBy(() -> new NotificationChannel("c1", null, "http://a.cn", null, null, null, null))
                .isInstanceOf(BusinessException.class);

        // Webhook 为空
        assertThatThrownBy(() -> new NotificationChannel("c1", Platform.WECOM, "   ", null, null, null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("测试渠道配置 backupChannels 默认值及属性转换")
    void testBackupChannelsConfig() {
        // 1. 默认空列表
        NotificationChannel channel = NotificationChannel.builder()
                .name("c-main")
                .platform(Platform.DINGTALK)
                .webhook("https://oapi.dingtalk.com/send")
                .build();
        assertThat(channel.getBackupChannels()).isNotNull().isEmpty();

        // 2. 显式配置 backupChannels
        NotificationChannel channelWithBackups = NotificationChannel.builder()
                .name("c-main")
                .platform(Platform.DINGTALK)
                .webhook("https://oapi.dingtalk.com/send")
                .backupChannels(List.of("b1", "b2"))
                .build();
        assertThat(channelWithBackups.getBackupChannels()).containsExactly("b1", "b2");

        // 3. fromProperties 包含 backupChannels
        NotificationChannelProperties props = new NotificationChannelProperties();
        props.setPlatform(Platform.FEISHU);
        props.setWebhook("https://open.feishu.cn/hook");
        props.setBackupChannels(List.of("feishu-backup"));

        NotificationChannel fromProps = NotificationChannel.fromProperties("feishu-main", props);
        assertThat(fromProps.getBackupChannels()).containsExactly("feishu-backup");
        assertThat(fromProps.toString()).contains("backupChannels=[feishu-backup]");
    }
}
