package io.github.atengk.boot.web.ratelimit.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;

/**
 * 接口限流防刷运行时配置属性
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.web.ratelimit")
public class RateLimiterProperties {

    /**
     * 是否启用接口限流防刷切面与存储引擎
     */
    private boolean enabled = true;

    /**
     * 底层存储（Redis/Redisson）发生网络或驱动故障时是否降级放行（默认 false 阻断请求；true 记录告警并降级至内存存储）
     */
    private boolean failOpen = false;

    /**
     * 单机内存存储配置
     */
    @NestedConfigurationProperty
    private InMemory inMemory = new InMemory();

    /**
     * 单机内存存储配置参数
     */
    @Data
    public static class InMemory {

        /**
         * 内存最大限流令牌桶元素容量软上限（防止海量 IP 导致 OOM）
         */
        private int maxCapacity = 10000;

        /**
         * 后台定期过期淘汰扫描周期
         */
        private Duration cleanInterval = Duration.ofSeconds(60);
    }
}
