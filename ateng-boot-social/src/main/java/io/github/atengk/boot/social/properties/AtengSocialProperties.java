package io.github.atengk.boot.social.properties;

import io.github.atengk.boot.social.http.SocialProxyConfig;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
     * OAuth2 防伪 State 缓存默认过期时长，默认 5 分钟
     */
    private Duration stateTimeout = Duration.ofMinutes(5);

    /**
     * 两阶段绑定票据 BindTicket 默认过期时长，默认 5 分钟
     */
    private Duration bindTicketTimeout = Duration.ofMinutes(5);

    /**
     * Redis 防伪 State 缓存键前缀，默认 social:state:
     */
    private String stateCacheKeyPrefix = "social:state:";

    /**
     * Redis 绑定票据缓存键前缀，默认 social:bind_ticket:
     */
    private String bindTicketKeyPrefix = "social:bind_ticket:";

    /**
     * 单机本地 Caffeine 缓存最大条目数上限，默认 10000
     */
    private long maxInMemoryCacheSize = 10_000L;

    /**
     * 允许的授权回调重定向域名白名单列表（为空表示不限制，支持精确域名与 *.example.com 泛域名）
     */
    private List<String> allowedRedirectDomains = new ArrayList<>();

    /**
     * 全局默认出站代理配置（可选）
     */
    @NestedConfigurationProperty
    private SocialProxyConfig proxy;

    /**
     * 命名社交应用配置字典，Key 为自定义 appKey (如 wechat-pc, github, dingtalk-corp)
     */
    private Map<String, SocialAppItemProperties> apps = new LinkedHashMap<>();

    /**
     * 内置社交登录 REST 控制器端点配置
     */
    @NestedConfigurationProperty
    private ControllerConfig controller = new ControllerConfig();

    /**
     * 内置社交登录 REST 控制器端点配置参数
     */
    @Data
    public static class ControllerConfig {

        /**
         * 是否启用内置社交登录 REST 控制器，默认 false
         */
        private boolean enabled = false;

        /**
         * 内置控制器端点统一前缀，默认 /auth/social
         */
        private String prefix = "/auth/social";
    }
}
