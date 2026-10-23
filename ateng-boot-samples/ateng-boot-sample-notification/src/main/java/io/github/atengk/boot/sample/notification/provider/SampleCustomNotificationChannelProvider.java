package io.github.atengk.boot.sample.notification.provider;

import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.channel.NotificationChannelProvider;
import io.github.atengk.boot.notification.model.Platform;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 示例：外部动态渠道供给器 SPI 实现
 * 模拟从数据库或配置中心动态供给外部通知渠道
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
@Component
public class SampleCustomNotificationChannelProvider implements NotificationChannelProvider {

    public static final String DYNAMIC_CHANNEL_NAME = "dynamic-security-bot";

    @Override
    public NotificationChannel loadChannel(String channelName) {
        if (DYNAMIC_CHANNEL_NAME.equals(channelName)) {
            log.info("SampleCustomNotificationChannelProvider: 动态按需懒加载渠道 [{}]", DYNAMIC_CHANNEL_NAME);
            return NotificationChannel.builder()
                    .name(DYNAMIC_CHANNEL_NAME)
                    .platform(Platform.GENERIC)
                    .webhook("http://localhost:18080/webhook/dynamic-security")
                    .secret("DYNAMIC_SEC_KEY_8888")
                    .connectTimeout(Duration.ofSeconds(2))
                    .readTimeout(Duration.ofSeconds(3))
                    .permitsPerMinute(30)
                    .build();
        }
        return null;
    }
}
