package io.github.atengk.boot.social.properties;

import io.github.atengk.boot.social.http.SocialProxyConfig;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 社交登录与 OAuth2 自动装配配置属性
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.social")
public class AtengSocialProperties {

    /**
     * 是否启用社交登录模块，默认 true
     */
    private boolean enabled = true;

    /**
     * 全局 HTTP 连接超时时间，默认 10 秒
     */
    private Duration connectTimeout = Duration.ofSeconds(10);

    /**
     * 全局 HTTP 读取超时时间，默认 15 秒
     */
    private Duration readTimeout = Duration.ofSeconds(15);

    /**
     * 全局默认出站代理配置（可选）
     */
    @NestedConfigurationProperty
    private SocialProxyConfig proxy;

    /**
     * 命名社交应用配置字典，Key 为自定义 appKey (如 wechat-pc, github, dingtalk-corp)
     */
    @NestedConfigurationProperty
    private Map<String, SocialAppItemProperties> apps = new LinkedHashMap<>();
}
