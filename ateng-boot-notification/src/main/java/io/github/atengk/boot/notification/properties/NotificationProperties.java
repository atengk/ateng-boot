package io.github.atengk.boot.notification.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ateng-boot 协同通知全局配置属性
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.notification")
public class NotificationProperties {

    /**
     * 是否启用协同通知基础设施，默认开启
     */
    private boolean enabled = true;

    /**
     * 默认通知渠道名称，未显式指定渠道时默认投递至此通道
     */
    private String defaultChannel = "ops-alert";

    /**
     * 全局默认网络建立连接超时，默认 3 秒
     */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /**
     * 全局默认网络读取超时，默认 5 秒
     */
    private Duration readTimeout = Duration.ofSeconds(5);

    /**
     * 客户端主动频控配置
     */
    @NestedConfigurationProperty
    private NotificationRateLimitProperties rateLimit = new NotificationRateLimitProperties();

    /**
     * 声明式异常告警与防风暴抑制配置
     */
    @NestedConfigurationProperty
    private NotificationAlertProperties alert = new NotificationAlertProperties();

    /**
     * 多命名渠道配置映射表 (channelName -> NotificationChannelProperties)
     */
    private Map<String, NotificationChannelProperties> channels = new LinkedHashMap<>();
}
